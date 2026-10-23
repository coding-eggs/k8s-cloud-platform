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
 * PostgreSQL 适配器（CloudNativePG，`postgresql.cnpg.io/v1`）。
 *
 * <p>两种形态：
 * <ul>
 *   <li>SINGLE → {@code instances: 1}（单实例，无容错）；</li>
 *   <li>HA → {@code instances: 3/5}（一主多备，CNPG 自管 failover）。</li>
 * </ul>
 *
 * <p><b>凭据</b>：Operator 生成 {@code <cr>-app} Secret（含 user/password/host/port/dbname），平台不写口令。
 *
 * <p><b>镜像</b>：不写 {@code imageName}，用 Operator 自己的默认 PostgreSQL 镜像 —— 平台钉死镜像版本
 * 会让「升级 Operator」和「实例镜像」脱钩，反而更难维护。
 */
@Component
public class PostgresqlMiddlewareAdapter implements MiddlewareAdapter {

    private static final String GROUP = "postgresql.cnpg.io";
    private static final String VERSION = "v1";

    static final CustomResourceDefinitionContext CRD = new CustomResourceDefinitionContext.Builder()
            .withGroup(GROUP).withVersion(VERSION)
            .withKind("Cluster").withPlural("clusters")
            .withScope("Namespaced").build();

    @Override
    public String code() {
        return "postgresql";
    }

    @Override
    public boolean usesPlatformPassword() {
        return false;   // CNPG 生成 <cr>-app
    }

    @Override
    public List<CrSpec> buildCreate(MiddlewareCreateRequestDTO req) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("instances", instancesOf(req));
        spec.put("storage", storageOf(req));
        spec.put("resources", resourcesOf(req));
        // 不写 spec.monitoring.enablePodMonitor：CNPG 0.29 已把它标为 deprecated（"请自己管理 PodMonitor"）。
        // 平台统一用 Service + ServiceMonitor 采业务指标（见 metricsSpec），与其它中间件同一条路径。
        return List.of(new CrSpec(CRD, resource(req, "Cluster", spec)));
    }

    /**
     * 业务指标：CNPG 的 exporter 是 instance manager 内置的（容器端口名 {@code metrics} = 9187），
     * 平台按 pod 选择器建 Service + ServiceMonitor（真集群验证：服务端口 9187 抓取正常）。
     */
    @Override
    public MetricsSpec metricsSpec(String name, String kind, String instanceId) {
        return new MetricsSpec(9187, "/metrics", "http", statusSelector(name, kind));
    }

    @Override
    public List<CustomResourceDefinitionContext> contexts() {
        return List.of(CRD);
    }

    @Override
    public void applyScale(GenericKubernetesResource live, CustomResourceDefinitionContext context,
                           MiddlewareScaleRequestDTO req) {
        Map<String, Object> spec = specOf(live);
        if (req.getReplicas() != null) {
            if ("SINGLE".equals(req.getMode()) && req.getReplicas() != 1) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "单实例形态不支持调整节点数；要扩容请删除后按「主备」重建");
            }
            if (!"SINGLE".equals(req.getMode())) {
                spec.put("instances", Math.max(3, req.getReplicas()));
            }
        }
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("requests", nonNullMap("cpu", req.getCpuRequest(), "memory", req.getMemoryRequest()));
        resources.put("limits", nonNullMap("cpu", req.getCpuLimit(), "memory", req.getMemoryLimit()));
        spec.put("resources", resources);
    }

    @Override
    public String statusSelector(String name, String kind) {
        // 只数实例 Pod：initdb/join 等 Job Pod 也带 cnpg.io/cluster 标签，
        // 不过滤会把 Completed 的 Job 算进"未就绪"（真集群验证：3 实例被数成 5 个）
        return "cnpg.io/cluster=" + name + ",cnpg.io/podRole=instance";
    }

    @Override
    public Integer desiredPods(GenericKubernetesResource live, String kind) {
        Object instances = specOf(live).get("instances");
        return instances instanceof Number n ? n.intValue() : null;
    }

    @Override
    public String pvcSelector(String name, String instanceId) {
        return "cnpg.io/cluster=" + name;
    }

    // ==================== 内部 ====================

    private static int instancesOf(MiddlewareCreateRequestDTO req) {
        int replicas = req.getReplicas() == null ? 1 : req.getReplicas();
        return "HA".equals(req.getMode()) ? Math.max(3, replicas) : 1;
    }

    private static Map<String, Object> storageOf(MiddlewareCreateRequestDTO req) {
        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("size", req.getStorageSize() == null || req.getStorageSize().isBlank()
                ? "10Gi" : req.getStorageSize());
        if (req.getStorageClass() != null && !req.getStorageClass().isBlank()) {
            storage.put("storageClass", req.getStorageClass().trim());
        }
        return storage;
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
