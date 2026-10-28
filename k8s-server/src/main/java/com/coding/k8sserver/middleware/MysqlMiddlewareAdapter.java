package com.coding.k8sserver.middleware;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;
import com.coding.common.models.middleware.MiddlewareScaleRequestDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MySQL 适配器（Percona Operator for MySQL，`ps.percona.com/v1`）。
 *
 * <p>两种形态都用 **Group Replication**（单主、强一致）：
 * <ul>
 *   <li>SINGLE → {@code size: 1}（单成员组，无容错，开发/测试）；</li>
 *   <li>CLUSTER → {@code size: 3/5}（≥3 成员才有多数派）。</li>
 * </ul>
 * 异步主从（{@code clusterType: async}）是 Percona 的 tech preview，不进表单。
 *
 * <p><b>凭据</b>：Operator 自己生成 {@code <cr>-secrets}（root/xtrabackup 等），平台不写口令 Secret。
 */
@Component
public class MysqlMiddlewareAdapter implements MiddlewareAdapter {

    private static final String GROUP = "ps.percona.com";
    private static final String VERSION = "v1";
    private static final String IMAGE = "percona/percona-server:8.4";
    /** Percona 没有独立的 haproxy 仓库；入口镜像在 PXC operator 的 -haproxy tag 里 */
    private static final String HAPROXY_IMAGE = "percona/percona-xtradb-cluster-operator:1.14.1-haproxy";
    /** 业务指标的 exporter 边车（Percona 自己只支持 PMM，Prometheus 要自己挂） */
    static final String EXPORTER_IMAGE = "prom/mysqld-exporter:v0.15.1";
    static final int EXPORTER_PORT = 9104;
    static final Map<String, Object> EXPORTER_RESOURCES = Map.of(
            "requests", Map.of("cpu", "50m", "memory", "64Mi"),
            "limits", Map.of("cpu", "100m", "memory", "128Mi"));

    static final CustomResourceDefinitionContext CRD = new CustomResourceDefinitionContext.Builder()
            .withGroup(GROUP).withVersion(VERSION)
            .withKind("PerconaServerMySQL").withPlural("perconaservermysqls")
            .withScope("Namespaced").build();

    @Override
    public String code() {
        return "mysql";
    }

    @Override
    public boolean usesPlatformPassword() {
        return false;
    }

    @Override
    public List<CrSpec> buildCreate(MiddlewareCreateRequestDTO req) {
        int size = sizeOf(req);
        Map<String, Object> mysql = new LinkedHashMap<>();
        mysql.put("clusterType", "group-replication");
        mysql.put("size", size);
        mysql.put("image", Images.resolve(IMAGE, req));
        mysql.put("resources", resourcesOf(req));
        mysql.put("volumeSpec", volumeSpecOf(req));
        // 业务指标（默认关）：Percona 只有 PMM，没有 Prometheus exporter → 平台挂 mysqld_exporter 边车。
        // 用 Operator 自己生成的 monitor 用户（<cr>-secrets 的 monitor 键，权限刚好够导出）。
        if (Boolean.TRUE.equals(req.getMetricsEnabled())) {
            mysql.put("sidecars", List.of(exporterSidecar(req.getName(),
                    Images.resolve(EXPORTER_IMAGE, req))));
        }

        // 可变结构：applyScale 会同步改 haproxy.size
        Map<String, Object> haproxy = new LinkedHashMap<>();
        haproxy.put("enabled", true);
        haproxy.put("size", size);
        // CRD 校验要求启用 haproxy 时显式给 image（operator 本身不内置默认值）
        haproxy.put("image", Images.resolve(HAPROXY_IMAGE, req));
        // 配额要求每个容器都写 requests/limits（真集群验证：不写会被 quota 挡住，HAProxy Pod 起不来）
        haproxy.put("resources", resourcesOf(req));
        Map<String, Object> router = new LinkedHashMap<>();
        router.put("enabled", false);   // 默认启用；与 haproxy 同时开会直接被 CRD 拒
        Map<String, Object> proxy = new LinkedHashMap<>();
        proxy.put("haproxy", haproxy);
        proxy.put("router", router);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("mysql", mysql);
        // HAProxy 作为统一入口（主库写、副本读）
        spec.put("proxy", proxy);
        // Group Replication 的 CRD 校验默认要求 size>=3（含代理 size>=2）；
        // SINGLE 形态必须显式开 unsafeFlags，否则创建直接被 webhook 拒。
        if (size < 3) {
            Map<String, Object> unsafe = new LinkedHashMap<>();
            unsafe.put("mysqlSize", true);
            unsafe.put("proxySize", true);
            spec.put("unsafeFlags", unsafe);
        }
        return List.of(new CrSpec(CRD, resource(req, "PerconaServerMySQL", spec)));
    }

    /** 编辑时补开业务指标：给 mysql 组件补 mysqld_exporter 边车（幂等，Pod 会滚动重启）。 */
    @Override
    @SuppressWarnings("unchecked")
    public boolean ensureMetrics(GenericKubernetesResource live, CustomResourceDefinitionContext context,
                                 MiddlewareScaleRequestDTO req) {
        Map<String, Object> spec = specOf(live);
        if (!(spec.get("mysql") instanceof Map<?, ?>)) {
            return false;
        }
        Map<String, Object> mysql = (Map<String, Object>) spec.get("mysql");
        List<Object> sidecars = mysql.get("sidecars") instanceof List<?> list
                ? new java.util.ArrayList<>((List<Object>) list) : new java.util.ArrayList<>();
        boolean present = sidecars.stream().anyMatch(c -> c instanceof Map<?, ?> m
                && "mysqld-exporter".equals(m.get("name")));
        if (present) {
            return false;
        }
        sidecars.add(exporterSidecar(req.getName(), Images.resolve(EXPORTER_IMAGE, req)));
        mysql.put("sidecars", sidecars);
        return true;
    }

    @Override
    public List<CustomResourceDefinitionContext> contexts() {
        return List.of(CRD);
    }

    @Override
    public void applyScale(GenericKubernetesResource live, CustomResourceDefinitionContext context,
                           MiddlewareScaleRequestDTO req) {
        Map<String, Object> spec = specOf(live);
        Object mysqlObj = spec.get("mysql");
        if (!(mysqlObj instanceof Map<?, ?>)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "实例规格不可识别");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> mysql = (Map<String, Object>) mysqlObj;
        boolean single = "SINGLE".equals(req.getMode());
        if (req.getReplicas() != null) {
            if (single) {
                if (req.getReplicas() != 1) {
                    throw new CloudPlatformException(EnumResponseType.ERROR,
                            "单节点形态不支持调整节点数；要扩容请删除后按「高可用集群」重建");
                }
            } else {
                int target = Math.max(3, req.getReplicas());
                mysql.put("size", target);
                // HAProxy 入口跟数据节点同步扩，否则入口只有一个副本、没有冗余
                Object proxyObj = spec.get("proxy");
                if (proxyObj instanceof Map<?, ?> proxy
                        && ((Map<String, Object>) proxy).get("haproxy") instanceof Map<?, ?> haproxy) {
                    ((Map<String, Object>) haproxy).put("size", target);
                }
            }
        }
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("requests", nonNullMap("cpu", req.getCpuRequest(), "memory", req.getMemoryRequest()));
        resources.put("limits", nonNullMap("cpu", req.getCpuLimit(), "memory", req.getMemoryLimit()));
        mysql.put("resources", resources);
        // HAProxy 的资源也要跟着改：配额按容器校验，不同步会在下次滚动时被 quota 挡住
        Object proxyObj = spec.get("proxy");
        if (proxyObj instanceof Map<?, ?> proxy
                && ((Map<String, Object>) proxy).get("haproxy") instanceof Map<?, ?> haproxy) {
            ((Map<String, Object>) haproxy).put("resources", resources);
        }
    }

    @Override
    public String statusSelector(String name, String kind) {
        // 数据 Pod 与 HAProxy Pod 都算实例的组成部分（HAProxy 是统一入口，它挂了这个实例就用不了）；
        // Percona 给数据 Pod 打的 component 是 database（真集群验证确认），不要把 component 写死成 mysql
        return "app.kubernetes.io/instance=" + name;
    }

    @Override
    public Integer desiredPods(GenericKubernetesResource live, String kind) {
        Object mysql = specOf(live).get("mysql");
        if (mysql instanceof Map<?, ?> m && m.get("size") instanceof Number n) {
            // 每个数据节点配一个 HAProxy（buildCreate 里 size 对齐）
            return n.intValue() * 2;
        }
        return null;
    }

    @Override
    public String pvcSelector(String name, String instanceId) {
        // Percona 给数据卷打的标签
        return "app.kubernetes.io/instance=" + name;
    }

    /**
     * 业务指标：mysqld_exporter 边车在 9104。
     * <p>selector 带上 {@code component=database}（真集群验证：Percona 给数据 Pod 打的不是 mysql）：
     * HAProxy Pod 共用 instance 标签但没有 9104 端口，选进来只会得到一批 down 的 target。
     */
    @Override
    public MetricsSpec metricsSpec(String name, String kind, String instanceId) {
        return new MetricsSpec(EXPORTER_PORT, "/metrics", "http",
                "app.kubernetes.io/instance=" + name + ",app.kubernetes.io/component=database");
    }

    static Map<String, Object> exporterSidecar(String name, String image) {
        Map<String, Object> sidecar = new LinkedHashMap<>();
        sidecar.put("name", "mysqld-exporter");
        sidecar.put("image", image);
        sidecar.put("args", List.of("--mysqld.address=127.0.0.1:3306", "--mysqld.username=monitor"));
        sidecar.put("env", List.of(Map.of("name", "MYSQLD_EXPORTER_PASSWORD", "valueFrom",
                Map.of("secretKeyRef", Map.of("name", name + "-secrets", "key", "monitor")))));
        sidecar.put("ports", List.of(Map.of(
                "name", "metrics", "containerPort", EXPORTER_PORT, "protocol", "TCP")));
        sidecar.put("resources", EXPORTER_RESOURCES);
        return sidecar;
    }

    // ==================== 内部 ====================

    private static int sizeOf(MiddlewareCreateRequestDTO req) {
        int replicas = req.getReplicas() == null ? 1 : req.getReplicas();
        return "CLUSTER".equals(req.getMode()) ? Math.max(3, replicas) : 1;
    }

    private static Map<String, Object> volumeSpecOf(MiddlewareCreateRequestDTO req) {
        Map<String, Object> claimSpec = new LinkedHashMap<>();
        claimSpec.put("resources", Map.of("requests", Map.of("storage",
                req.getStorageSize() == null || req.getStorageSize().isBlank() ? "10Gi" : req.getStorageSize())));
        if (req.getStorageClass() != null && !req.getStorageClass().isBlank()) {
            claimSpec.put("storageClassName", req.getStorageClass());
        }
        return Map.of("persistentVolumeClaim", claimSpec);
    }

    private static Map<String, Object> resourcesOf(MiddlewareCreateRequestDTO req) {
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("requests", nonNullMap("cpu", req.getCpuRequest(), "memory", req.getMemoryRequest()));
        resources.put("limits", nonNullMap("cpu", req.getCpuLimit(), "memory", req.getMemoryLimit()));
        return resources;
    }

    private GenericKubernetesResource resource(MiddlewareCreateRequestDTO req, String kind,
                                               Map<String, Object> spec) {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setApiVersion(GROUP + "/" + VERSION);
        res.setKind(kind);
        ObjectMeta meta = new ObjectMeta();
        meta.setName(req.getName());
        meta.setNamespace(req.getNamespace());
        meta.setLabels(Map.of(
                "platform.coding/managed-by", "middleware",
                "platform.coding/middleware-instance",
                req.getInstanceId() == null ? "-" : req.getInstanceId()));
        res.setMetadata(meta);
        res.setAdditionalProperty("spec", spec);
        return res;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> specOf(GenericKubernetesResource res) {
        Object spec = res.getAdditionalProperties().get("spec");
        return spec instanceof Map ? (Map<String, Object>) spec : new LinkedHashMap<>();
    }

    private static Map<String, Object> nonNullMap(String k1, String v1, String k2, String v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (v1 != null && !v1.isBlank()) {
            m.put(k1, v1);
        }
        if (v2 != null && !v2.isBlank()) {
            m.put(k2, v2);
        }
        return m;
    }
}
