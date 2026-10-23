package com.coding.k8sserver.middleware;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.middleware.MiddlewareOperatorStatusDTO;
import com.coding.k8score.factory.KubernetesClientFactory;
import com.coding.k8sserver.helm.ChartBundle;
import com.coding.k8sserver.helm.HelmResult;
import com.coding.k8sserver.helm.HelmRunner;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.stereotype.Component;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 中间件 Operator 的安装与状态判定。
 *
 * <p>安装：helm {@code upgrade --install}（幂等），release = {@code platform-middleware-<code>}，
 * 命名空间 {@code platform-middleware-system}（Helm 自带 {@code --create-namespace}）。
 * values 来自 {@link MiddlewareOperatorCatalog.Entry#helmValues()}——Percona/Strimzi 必须显式
 * 打开"监听全部命名空间"，否则租户命名空间里的实例 CR 不会被 reconcile。
 *
 * <p><b>状态判定不看标签</b>：不同的 Operator chart 给自身 Deployment 打的标签各不相同
 * （Strimzi 用 {@code release:}，ECK 甚至是 StatefulSet）。这里改为解析本 release 的
 * {@code helm get manifest}，取出其中的 Deployment / StatefulSet 逐个判就绪 —— chart 无关且可靠。
 */
@Component
public class MiddlewareOperatorInstaller {

    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private final HelmRunner helm;
    private final ChartBundle charts;
    private final KubernetesClientFactory clients;
    private final MiddlewareOperatorCatalog catalog;

    public MiddlewareOperatorInstaller(HelmRunner helm, ChartBundle charts,
                                       KubernetesClientFactory clients,
                                       MiddlewareOperatorCatalog catalog) {
        this.helm = helm;
        this.charts = charts;
        this.clients = clients;
        this.catalog = catalog;
    }

    public MiddlewareOperatorStatusDTO install(String clusterId, String code) {
        installHelm(clusterId, code);
        return status(clusterId, code);
    }

    /** 只做 helm 安装（拆出来是为了单测能避开 fabric8 的深度 mock）。 */
    void installHelm(String clusterId, String code) {
        var entry = catalog.require(code);
        assertCrdsNotForeign(clusterId, entry);
        Path chartDir = charts.chartDir("middleware", entry.code(), entry.chartVersion());
        Path values;
        try {
            values = Files.createTempFile("mw-operator-", ".yaml");
            Files.writeString(values, entry.helmValues() == null ? "" : entry.helmValues());
        } catch (IOException e) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "无法创建临时 values：" + e.getMessage());
        }
        try {
            String kubeconfig = clients.adminKubeconfig(clusterId);
            HelmResult r = helm.apply(kubeconfig, chartDir, values,
                    MiddlewareOperatorCatalog.releaseName(code), MiddlewareOperatorCatalog.NAMESPACE,
                    new HelmRunner.InstallOptions(false, false,
                            Map.of("platform.coding/managed-by", "middleware-operator")));
            if (!r.ok()) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "Operator 安装失败：" + tail(r.output()));
            }
        } finally {
            try {
                Files.deleteIfExists(values);
            } catch (IOException ignored) {
                // 临时文件删不掉不影响结果
            }
        }
    }

    public MiddlewareOperatorStatusDTO status(String clusterId, String code) {
        var entry = catalog.require(code);
        KubernetesClient client = clients.getAdminClient(clusterId);

        boolean crds = entry.requiredCrds().stream()
                .allMatch(n -> crdOrNull(client, n) != null);

        HelmResult manifest = helm.manifest(clients.adminKubeconfig(clusterId),
                MiddlewareOperatorCatalog.releaseName(code), MiddlewareOperatorCatalog.NAMESPACE);
        boolean releaseExists = manifest.ok();
        List<Workload> workloads = releaseExists ? workloadsOf(manifest.output()) : List.of();
        boolean ready = crds && !workloads.isEmpty()
                && workloads.stream().allMatch(w -> workloadReady(client, w.kind(), w.name()));

        MiddlewareOperatorStatusDTO out = new MiddlewareOperatorStatusDTO();
        out.setCode(code);
        out.setChartVersion(entry.chartVersion());
        // 「已安装」只认本平台自己的 helm release：CRD 可能是别人早先装的（真集群上 ECK 就是），
        // 把 CRD 存在算作已安装会让卡片状态与事实相反
        out.setInstalled(releaseExists);
        out.setReady(ready);
        // 「有没有升级可做」以集群里实际的 chart 版本为准：Deployment/StatefulSet 上有 helm.sh/chart 标签
        String installedVersion = releaseExists ? installedChartVersion(client, workloads) : null;
        out.setInstalledChartVersion(installedVersion);
        out.setUpgradeAvailable(installedVersion != null
                && !installedVersion.equals(entry.chartVersion()));
        out.setMessage(ready ? "运行中"
                : !releaseExists ? "Operator 尚未安装（helm release 不存在）"
                : !crds ? "CRD 未就绪（安装可能仍在进行）"
                : workloads.isEmpty() ? "release 清单里没有 Deployment/StatefulSet"
                : "Operator 工作负载未就绪");
        return out;
    }

    /**
     * CRD 归属预检（spec §4.2）：已存在且不是本 release 管理的 CRD 一律拒绝。
     *
     * <p>为什么必须拦：helm 4 用 SSA 装 `crds/`，而 apply 带 `--force-conflicts` ——
     * 不拦的话会把别人（手工装 / 另一个 release 名）的 CRD 所有权抢过来，
     * 可能把 schema 降级，连带影响已经存在的实例。
     */
    void assertCrdsNotForeign(String clusterId, MiddlewareOperatorCatalog.Entry entry) {
        String release = MiddlewareOperatorCatalog.releaseName(entry.code());
        KubernetesClient client = clients.getAdminClient(clusterId);
        for (String name : entry.requiredCrds()) {
            var crd = crdOrNull(client, name);
            if (crd == null || crd.getMetadata() == null) {
                continue;
            }
            var labels = crd.getMetadata().getLabels() == null ? Map.<String, String>of()
                    : crd.getMetadata().getLabels();
            var anns = crd.getMetadata().getAnnotations() == null ? Map.<String, String>of()
                    : crd.getMetadata().getAnnotations();
            String owner = anns.get("meta.helm.sh/release-name");
            if (owner != null && !owner.equals(release)) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "CRD " + name + " 已由另一个 Helm release（" + owner + "）管理，拒绝接管");
            }
            if (owner == null && !entry.crdsShipInCrdsDir()
                    && !"Helm".equals(labels.get("app.kubernetes.io/managed-by"))) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "CRD " + name + " 已存在但不由 Helm 管理，拒绝接管（请先手工清理或改由平台安装）");
            }
        }
    }

    /**
     * 从工作负载的 {@code helm.sh/chart} 标签（格式 {@code <chart 名>-<版本>}）里取实际安装的版本。
     * 读不到返回 null —— 卡片就按「已安装、版本未知」展示，不误报可升级。
     */
    String installedChartVersion(KubernetesClient client, List<Workload> workloads) {
        if (client == null) {
            return null;
        }
        for (Workload w : workloads) {
            Map<String, String> labels = null;
            if ("StatefulSet".equals(w.kind())) {
                var sts = client.apps().statefulSets()
                        .inNamespace(MiddlewareOperatorCatalog.NAMESPACE).withName(w.name()).get();
                labels = sts == null || sts.getMetadata() == null ? null : sts.getMetadata().getLabels();
            } else {
                var d = client.apps().deployments()
                        .inNamespace(MiddlewareOperatorCatalog.NAMESPACE).withName(w.name()).get();
                labels = d == null || d.getMetadata() == null ? null : d.getMetadata().getLabels();
            }
            String chart = labels == null ? null : labels.get("helm.sh/chart");
            String version = versionOf(chart);
            if (version != null) {
                return version;
            }
        }
        return null;
    }

    /** {@code eck-operator-3.5.0} → {@code 3.5.0}（取末尾的 semver） */
    static String versionOf(String helmChartLabel) {
        if (helmChartLabel == null) {
            return null;
        }
        var m = java.util.regex.Pattern.compile("(\\d+\\.\\d+\\.\\d+)$").matcher(helmChartLabel.trim());
        return m.find() ? m.group(1) : null;
    }

    /** 读一个 CRD（包级：单测覆写它来跳过 fabric8 客户端链）。 */
    io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition crdOrNull(
            KubernetesClient client, String name) {
        return client.apiextensions().v1().customResourceDefinitions().withName(name).get();
    }

    /** 判一个工作负载是否就绪（包级：单测覆写）。 */
    boolean workloadReady(KubernetesClient client, String kind, String name) {
        if ("StatefulSet".equals(kind)) {
            var sts = client.apps().statefulSets()
                    .inNamespace(MiddlewareOperatorCatalog.NAMESPACE).withName(name).get();
            if (sts == null || sts.getSpec() == null) {
                return false;
            }
            int want = sts.getSpec().getReplicas() == null ? 1 : sts.getSpec().getReplicas();
            if (want <= 0) {
                return false;
            }
            return sts.getStatus() != null
                    && sts.getStatus().getReadyReplicas() != null
                    && sts.getStatus().getReadyReplicas() >= want;
        }
        Deployment d = client.apps().deployments()
                .inNamespace(MiddlewareOperatorCatalog.NAMESPACE).withName(name).get();
        return deploymentReady(d);
    }

    private static boolean deploymentReady(Deployment d) {
        if (d == null || d.getSpec() == null || d.getSpec().getReplicas() == null
                || d.getSpec().getReplicas() <= 0) {
            return false;
        }
        if (d.getStatus() == null || d.getStatus().getReadyReplicas() == null) {
            return false;
        }
        return Objects.equals(d.getStatus().getReadyReplicas(), d.getSpec().getReplicas());
    }

    /** 从 helm get manifest 里取出 Deployment / StatefulSet 的名字（chart 无关）。 */
    @SuppressWarnings("unchecked")
    List<Workload> workloadsOf(String manifest) {
        List<Workload> out = new ArrayList<>();
        if (manifest == null || manifest.isBlank()) {
            return out;
        }
        for (String doc : manifest.split("(?m)^---\\s*$")) {
            if (doc.isBlank()) {
                continue;
            }
            try {
                Map<String, Object> m = YAML.readValue(doc, Map.class);
                Object kind = m.get("kind");
                if (!"Deployment".equals(kind) && !"StatefulSet".equals(kind)) {
                    continue;
                }
                Object meta = m.get("metadata");
                if (meta instanceof Map<?, ?> mm && mm.get("name") != null) {
                    out.add(new Workload((String) kind, (String) mm.get("name")));
                }
            } catch (RuntimeException ignored) {
                // 单个文档解析失败（如注释放错位置）不阻塞其它文档
            }
        }
        return out;
    }

    private static String tail(String output) {
        if (output == null) {
            return "";
        }
        String s = output.strip();
        return s.length() <= 800 ? s : s.substring(s.length() - 800);
    }

    /** release 清单里的一个工作负载。 */
    record Workload(String kind, String name) {
    }
}
