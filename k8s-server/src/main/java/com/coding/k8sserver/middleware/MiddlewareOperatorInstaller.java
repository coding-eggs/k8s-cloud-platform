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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 中间件 Operator 的安装与状态判定。
 *
 * <p>安装：helm {@code upgrade --install}（幂等），release = {@code platform-middleware-<code>}，
 * 命名空间 {@code platform-middleware-system}（Helm 自带 {@code --create-namespace}）。
 *
 * <p>判定（见 spec §2.1）：CRD 齐全（Established 由 API 存在性近似）+ 该 release 的 Operator
 * Deployment 就绪。只看 release 不看 CRD 会把"卸载后 CRD 残留"误判成未安装。
 */
@Component
public class MiddlewareOperatorInstaller {

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
        Path chartDir = charts.chartDir("middleware", entry.code(), entry.chartVersion());
        Path values;
        try {
            values = Files.createTempFile("mw-operator-", ".yaml");
            Files.writeString(values, "");
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
                .allMatch(n -> client.apiextensions().v1().customResourceDefinitions().withName(n).get() != null);

        List<Deployment> deps = client.apps().deployments()
                .inNamespace(MiddlewareOperatorCatalog.NAMESPACE)
                .withLabel("app.kubernetes.io/instance", MiddlewareOperatorCatalog.releaseName(code))
                .list().getItems();

        boolean ready = crds && !deps.isEmpty() && deps.stream().allMatch(MiddlewareOperatorInstaller::deploymentReady);

        MiddlewareOperatorStatusDTO out = new MiddlewareOperatorStatusDTO();
        out.setCode(code);
        out.setChartVersion(entry.chartVersion());
        out.setInstalled(crds || !deps.isEmpty());
        out.setReady(ready);
        out.setMessage(ready ? "运行中"
                : !crds ? "CRD 未就绪（Operator 未安装或安装未完成）"
                : deps.isEmpty() ? "未找到 Operator Deployment"
                : "Operator Deployment 未就绪");
        return out;
    }

    private static boolean deploymentReady(Deployment d) {
        if (d.getSpec() == null || d.getSpec().getReplicas() == null || d.getSpec().getReplicas() <= 0) {
            return false;
        }
        if (d.getStatus() == null || d.getStatus().getReadyReplicas() == null) {
            return false;
        }
        return Objects.equals(d.getStatus().getReadyReplicas(), d.getSpec().getReplicas());
    }

    private static String tail(String output) {
        if (output == null) {
            return "";
        }
        String s = output.strip();
        return s.length() <= 800 ? s : s.substring(s.length() - 800);
    }
}
