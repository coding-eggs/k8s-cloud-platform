package com.coding.k8sserver.middleware;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;
import com.coding.k8score.factory.KubernetesClientFactory;
import com.coding.k8sserver.helm.ChartBundle;
import com.coding.k8sserver.helm.HelmResult;
import com.coding.k8sserver.helm.HelmRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Nacos（chart 安装器）。
 *
 * <p>Nacos 不是 Operator：实例 = 一次 `helm upgrade --install`，release 名就是平台生成的实例名。
 * 删除走 `helm uninstall`；PVC 由 chart 的 persistence 控制，不随 release 删除。
 *
 * <p>关键 values（对着 nacos chart 1.2.0 的 values.yaml 写）：
 * <ul>
 *   <li>{@code mode: standalone|cluster}；</li>
 *   <li>{@code nacos.replicaCount}；Nacos 3.x 集群用内嵌 JRaft 存储，不依赖外置 MySQL；</li>
 *   <li>{@code service.type: ClusterIP} —— chart 默认 NodePort/30000，多实例会撞端口；</li>
 *   <li>{@code resources} 是顶层键（不是 {@code nacos.resources}）。</li>
 * </ul>
 */
@Component
public class NacosChartInstaller {

    static final String CHART_VERSION = "1.2.0";
    private static final String IMAGE_REPOSITORY = "nacos/nacos-server";
    private static final String IMAGE_TAG = "v3.2.4";

    private final HelmRunner helm;
    private final ChartBundle charts;
    private final KubernetesClientFactory clients;

    public NacosChartInstaller(HelmRunner helm, ChartBundle charts, KubernetesClientFactory clients) {
        this.helm = helm;
        this.charts = charts;
        this.clients = clients;
    }

    public void create(MiddlewareCreateRequestDTO req) {
        Path values = writeValues(valuesFor(req));
        try {
            HelmResult r = helm.apply(clients.adminKubeconfig(req.getClusterId()),
                    charts.chartDir("middleware", "nacos", CHART_VERSION),
                    values, req.getName(), req.getNamespace(),
                    new HelmRunner.InstallOptions(false, false, Map.of(
                            "platform.coding/managed-by", "middleware",
                            "platform.coding/middleware-instance",
                            req.getInstanceId() == null ? "-" : req.getInstanceId())));
            if (!r.ok()) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "Nacos 安装失败：" + tail(r.output()));
            }
        } finally {
            deleteQuietly(values);
        }
    }

    /** release 是否存在（Nacos 的 Service 名是 chart 硬编码的，不能拿实例名去查）。 */
    public boolean exists(String clusterId, String namespace, String release) {
        return helm.manifest(clients.adminKubeconfig(clusterId), release, namespace).ok();
    }

    public void delete(String clusterId, String namespace, String release) {
        HelmResult r = helm.uninstall(clients.adminKubeconfig(clusterId), release, namespace);
        if (!r.ok() && !r.output().contains("not found")) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "Nacos 卸载失败：" + tail(r.output()));
        }
    }

    /**
     * 扩容暂不支持：Nacos 的 values 含上一次安装的存储等设置，而扩容请求只带副本/资源，
     * 重新 upgrade 会把没带上的字段冲回默认。需要扩容时删除重建（数据在 PVC 上，可保留）。
     */
    public void scale(String clusterId, String code) {
        throw new CloudPlatformException(EnumResponseType.ERROR,
                "Nacos 暂不支持在线扩容；请删除实例后按新规格重建（保留数据卷可沿用原 PVC）");
    }

    /** 生成 helm values（测试直接断言字符串内容）。 */
    String valuesFor(MiddlewareCreateRequestDTO req) {
        int replicas = Math.max(1, req.getReplicas() == null ? 1 : req.getReplicas());
        String mode = "CLUSTER".equals(req.getMode()) ? "cluster" : "standalone";
        StringBuilder sb = new StringBuilder();
        // ⚠️ chart 读的是 global.mode（不是顶层 mode）；写错会静默渲染成 standalone
        sb.append("global:\n");
        sb.append("  mode: ").append(mode).append('\n');
        sb.append("nacos:\n");
        sb.append("  replicaCount: ").append(replicas).append('\n');
        sb.append("  image:\n");
        sb.append("    repository: ").append(IMAGE_REPOSITORY).append('\n');
        sb.append("    tag: ").append(IMAGE_TAG).append('\n');
        sb.append("  storage:\n");
        sb.append("    type: embedded\n");
        sb.append("resources:\n");
        appendResources(sb, req);
        boolean persist = req.getStorageSize() != null && !req.getStorageSize().isBlank();
        sb.append("persistence:\n");
        sb.append("  enabled: ").append(persist).append('\n');
        if (persist) {
            sb.append("  data:\n");
            sb.append("    accessModes:\n");
            sb.append("      - ReadWriteOnce\n");
            sb.append("    resources:\n");
            sb.append("      requests:\n");
            sb.append("        storage: ").append(req.getStorageSize()).append('\n');
            // chart 默认 storageClassName: manual —— 必须显式覆盖。
            // ⚠️ 留空要写 null，不能写空串 "": 空串在 K8s 里表示"不申请 StorageClass"，
            // PVC 会永远 Pending（真集群验证踩过）；null 才是"用集群默认 SC"。
            String sc = req.getStorageClass() == null ? "" : req.getStorageClass().trim();
            sb.append("    storageClassName: ").append(sc.isEmpty() ? "null" : "\"" + sc + "\"").append('\n');
        }
        // chart 默认 NodePort/30000：多实例会撞端口，必须固定 ClusterIP
        sb.append("service:\n");
        sb.append("  type: ClusterIP\n");
        sb.append("  port: 8848\n");
        return sb.toString();
    }

    private static void appendResources(StringBuilder sb, MiddlewareCreateRequestDTO req) {
        boolean hasRequests = notBlank(req.getCpuRequest()) || notBlank(req.getMemoryRequest());
        boolean hasLimits = notBlank(req.getCpuLimit()) || notBlank(req.getMemoryLimit());
        if (!hasRequests && !hasLimits) {
            sb.append("  {}\n");
            return;
        }
        if (hasRequests) {
            sb.append("  requests:\n");
            appendIfPresent(sb, "cpu", req.getCpuRequest());
            appendIfPresent(sb, "memory", req.getMemoryRequest());
        }
        if (hasLimits) {
            sb.append("  limits:\n");
            appendIfPresent(sb, "cpu", req.getCpuLimit());
            appendIfPresent(sb, "memory", req.getMemoryLimit());
        }
    }

    private static void appendIfPresent(StringBuilder sb, String key, String value) {
        if (notBlank(value)) {
            sb.append("    ").append(key).append(": ").append(value).append('\n');
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static Path writeValues(String content) {
        try {
            Path p = Files.createTempFile("nacos-values-", ".yaml");
            Files.writeString(p, content);
            return p;
        } catch (IOException e) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "无法写临时 values：" + e.getMessage());
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // 删不掉不影响结果
        }
    }

    private static String tail(String output) {
        if (output == null) {
            return "";
        }
        String s = output.strip();
        return s.length() <= 800 ? s : s.substring(s.length() - 800);
    }
}
