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
 * RabbitMQ 适配器（RabbitMQ Cluster Operator，`rabbitmq.com/v1beta1`）。
 *
 * <p>两种形态：{@code SINGLE → replicas: 1}、{@code CLUSTER → replicas ≥3}（Quorum 队列需要多数派）。
 *
 * <p><b>凭据</b>：Operator 生成 {@code <cr>-default-user} Secret（username/password），平台不写口令。
 */
@Component
public class RabbitmqMiddlewareAdapter implements MiddlewareAdapter {

    private static final String GROUP = "rabbitmq.com";
    private static final String VERSION = "v1beta1";

    static final CustomResourceDefinitionContext CRD = new CustomResourceDefinitionContext.Builder()
            .withGroup(GROUP).withVersion(VERSION)
            .withKind("RabbitmqCluster").withPlural("rabbitmqclusters")
            .withScope("Namespaced").build();

    @Override
    public String code() {
        return "rabbitmq";
    }

    @Override
    public boolean usesPlatformPassword() {
        return false;   // Operator 生成 <cr>-default-user
    }

    @Override
    public List<CrSpec> buildCreate(MiddlewareCreateRequestDTO req) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("replicas", replicasOf(req));
        spec.put("resources", resourcesOf(req));
        spec.put("persistence", persistenceOf(req));
        return List.of(new CrSpec(CRD, resource(req, "RabbitmqCluster", spec)));
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
                        "单节点形态不支持调整节点数；要扩容请删除后按「集群」重建");
            }
            if (!"SINGLE".equals(req.getMode())) {
                spec.put("replicas", Math.max(3, req.getReplicas()));
            }
        }
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("requests", nonNullMap("cpu", req.getCpuRequest(), "memory", req.getMemoryRequest()));
        resources.put("limits", nonNullMap("cpu", req.getCpuLimit(), "memory", req.getMemoryLimit()));
        spec.put("resources", resources);
    }

    @Override
    public String statusSelector(String name, String kind) {
        // Cluster Operator 给 StatefulSet/Pod/PVC 打的是 name=<集群名>（真集群验证：不是 <名称>-rabbitmq）
        return "app.kubernetes.io/name=" + name + ",app.kubernetes.io/component=rabbitmq";
    }

    @Override
    public Integer desiredPods(GenericKubernetesResource live, String kind) {
        Object replicas = specOf(live).get("replicas");
        return replicas instanceof Number n ? n.intValue() : null;
    }

    @Override
    public String pvcSelector(String name, String instanceId) {
        return "app.kubernetes.io/name=" + name;
    }

    /**
     * 业务指标：`rabbitmq_prometheus` 是 Operator 的必备插件（默认启用），指标端点 15692；
     * CR 不需要额外配置，平台只负责建采集资源。selector 与状态判定一致，只选 RabbitMQ Pod。
     */
    @Override
    public MetricsSpec metricsSpec(String name, String kind, String instanceId) {
        return new MetricsSpec(15692, "/metrics", "http", statusSelector(name, kind));
    }

    // ==================== 内部 ====================

    private static int replicasOf(MiddlewareCreateRequestDTO req) {
        int replicas = req.getReplicas() == null ? 1 : req.getReplicas();
        return "CLUSTER".equals(req.getMode()) ? Math.max(3, replicas) : 1;
    }

    private static Map<String, Object> persistenceOf(MiddlewareCreateRequestDTO req) {
        Map<String, Object> persistence = new LinkedHashMap<>();
        persistence.put("storage", req.getStorageSize() == null || req.getStorageSize().isBlank()
                ? "10Gi" : req.getStorageSize());
        if (req.getStorageClass() != null && !req.getStorageClass().isBlank()) {
            persistence.put("storageClassName", req.getStorageClass().trim());
        }
        return persistence;
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
