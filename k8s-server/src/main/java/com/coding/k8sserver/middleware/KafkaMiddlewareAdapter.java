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
 * Kafka 适配器（Strimzi，`kafka.strimzi.io/v1`，纯 KRaft）。
 *
 * <p>Strimzi 1.x 的实例是**两个 CR**：
 * <ul>
 *   <li>{@code KafkaNodePool}：controller+broker 双角色节点池，副本数与存储都在这；</li>
 *   <li>{@code Kafka}：必须带注解 {@code strimzi.io/node-pools: enabled}，否则节点池不生效。</li>
 * </ul>
 *
 * <p>KRaft 的仲裁要求 ≥3 个节点：<b>2 节点没有容错意义</b>，所以 replicas 下限固定 3。
 */
@Component
public class KafkaMiddlewareAdapter implements MiddlewareAdapter {

    private static final String GROUP = "kafka.strimzi.io";
    private static final String VERSION = "v1";
    /** Strimzi 1.3.0 支持 4.2.x / 4.3.x；写 4.4.0 会被 operator 直接拒（UnsupportedKafkaVersionException） */
    private static final String DEFAULT_KAFKA_VERSION = "4.3.1";

    static final CustomResourceDefinitionContext KAFKA = new CustomResourceDefinitionContext.Builder()
            .withGroup(GROUP).withVersion(VERSION).withKind("Kafka")
            .withPlural("kafkas").withScope("Namespaced").build();

    static final CustomResourceDefinitionContext NODE_POOL = new CustomResourceDefinitionContext.Builder()
            .withGroup(GROUP).withVersion(VERSION).withKind("KafkaNodePool")
            .withPlural("kafkanodepools").withScope("Namespaced").build();

    @Override
    public String code() {
        return "kafka";
    }

    @Override
    public boolean usesPlatformPassword() {
        return false;   // 内网 KRaft 监听器不开认证
    }

    @Override
    public List<CrSpec> buildCreate(MiddlewareCreateRequestDTO req) {
        int replicas = Math.max(3, req.getReplicas() == null ? 3 : req.getReplicas());
        String kafkaVersion = req.getVersion() == null || req.getVersion().isBlank()
                ? DEFAULT_KAFKA_VERSION : req.getVersion();

        Map<String, Object> pool = new LinkedHashMap<>();
        pool.put("replicas", replicas);
        pool.put("roles", List.of("controller", "broker"));
        Map<String, Object> volume = new LinkedHashMap<>();
        volume.put("id", 0);
        volume.put("type", "persistent-claim");
        volume.put("size", storageSize(req));
        volume.put("kraftMetadata", "shared");
        if (req.getStorageClass() != null && !req.getStorageClass().isBlank()) {
            // CRD 字段名是 class；漏了它会静默落到集群默认 SC
            volume.put("class", req.getStorageClass().trim());
        }
        pool.put("storage", Map.of("type", "jbod", "volumes", List.of(volume)));
        pool.put("resources", resourcesOf(req));
        GenericKubernetesResource poolRes = resource(req, "KafkaNodePool", req.getName(), pool);
        // Strimzi 按标签 strimzi.io/cluster 关联 NodePool 与 Kafka；只靠 CR 同名不算数
        //（漏了它 Kafka 永远 NotReady：No KafkaNodePools found）
        Map<String, String> poolLabels = new LinkedHashMap<>(poolRes.getMetadata().getLabels());
        poolLabels.put("strimzi.io/cluster", req.getName());
        poolRes.getMetadata().setLabels(poolLabels);

        int rf = Math.min(replicas, 3);
        Map<String, Object> kafka = new LinkedHashMap<>();
        kafka.put("version", kafkaVersion);
        kafka.put("listeners", listeners());
        // 业务指标（默认关）：Strimzi 1.3 的 Metrics Reporter（不用 ConfigMap，也不额外加边车），指标在 9404
        if (Boolean.TRUE.equals(req.getMetricsEnabled())) {
            kafka.put("metricsConfig", Map.of("type", "strimziMetricsReporter"));
        }
        kafka.put("config", Map.of(
                "offsets.topic.replication.factor", rf,
                "transaction.state.log.replication.factor", rf,
                "transaction.state.log.min.isr", Math.min(replicas, 2),
                "default.replication.factor", rf,
                "min.insync.replicas", Math.min(replicas, 2)));
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("kafka", kafka);
        spec.put("entityOperator", Map.of("topicOperator", Map.of(), "userOperator", Map.of()));
        GenericKubernetesResource kafkaRes = resource(req, "Kafka", req.getName(), spec);
        // KRaft 节点池必须显式启用（Strimzi 1.x）
        kafkaRes.getMetadata().setAnnotations(Map.of("strimzi.io/node-pools", "enabled"));

        return List.of(new CrSpec(NODE_POOL, poolRes), new CrSpec(KAFKA, kafkaRes));
    }

    /**
     * 两条监听器：{@code plain}（9092，保持原样，存量客户端不受影响）+ {@code scram}（9093，
     * SCRAM-SHA-512 认证）。用户管理只认第二条 —— 走明文的那条没有"用户"这一说。
     *
     * <p>有意**不**开 {@code kafka.authorization}：一开授权，现有走 PLAINTEXT 的客户端（匿名用户）
     * 会被全部拒掉；那是接入迁移，不在本批。
     */
    static List<Map<String, Object>> listeners() {
        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("name", "plain");
        plain.put("port", 9092);
        plain.put("type", "internal");
        plain.put("tls", false);
        Map<String, Object> scram = new LinkedHashMap<>();
        scram.put("name", "scram");
        scram.put("port", 9093);
        scram.put("type", "internal");
        scram.put("tls", false);
        scram.put("authentication", Map.of("type", "scram-sha-512"));
        return List.of(plain, scram);
    }

    /** 编辑时补开业务指标：给 Kafka CR 补上 metricsConfig（幂等，Pod 会滚动重启）。 */
    @Override
    @SuppressWarnings("unchecked")
    public boolean ensureMetrics(GenericKubernetesResource live, CustomResourceDefinitionContext context,
                                 MiddlewareScaleRequestDTO req) {
        if (!"Kafka".equals(context.getKind())) {
            return false;
        }
        Map<String, Object> spec = specOf(live);
        Object kafkaObj = spec.get("kafka");
        if (!(kafkaObj instanceof Map<?, ?>)) {
            return false;
        }
        Map<String, Object> kafka = (Map<String, Object>) kafkaObj;
        if (kafka.containsKey("metricsConfig")) {
            return false;
        }
        kafka.put("metricsConfig", Map.of("type", "strimziMetricsReporter"));
        return true;
    }

    @Override
    public List<CustomResourceDefinitionContext> contexts() {
        return List.of(NODE_POOL, KAFKA);
    }

    @Override
    public void applyScale(GenericKubernetesResource live, CustomResourceDefinitionContext context,
                           MiddlewareScaleRequestDTO req) {
        if (!"KafkaNodePool".equals(context.getKind())) {
            return;   // Kafka 本体没有可扩字段
        }
        Map<String, Object> spec = specOf(live);
        if (req.getReplicas() != null) {
            spec.put("replicas", Math.max(3, req.getReplicas()));
        }
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("requests", nonNullMap("cpu", req.getCpuRequest(), "memory", req.getMemoryRequest()));
        resources.put("limits", nonNullMap("cpu", req.getCpuLimit(), "memory", req.getMemoryLimit()));
        spec.put("resources", resources);
    }

    @Override
    public String statusSelector(String name, String kind) {
        // strimzi.io/name=<cluster>-kafka 只命中 broker/controller Pod，不把 Entity Operator 算进来
        return "strimzi.io/cluster=" + name + ",strimzi.io/name=" + name + "-kafka";
    }

    @Override
    public String pvcSelector(String name, String instanceId) {
        return "strimzi.io/cluster=" + name;
    }

    /** 业务指标：Strimzi Metrics Reporter 在 9404 暴露 /metrics；selector 只选 broker/controller Pod。 */
    @Override
    public MetricsSpec metricsSpec(String name, String kind, String instanceId) {
        return new MetricsSpec(9404, "/metrics", "http", statusSelector(name, kind));
    }

    // ==================== 内部 ====================

    private static String storageSize(MiddlewareCreateRequestDTO req) {
        return req.getStorageSize() == null || req.getStorageSize().isBlank() ? "10Gi" : req.getStorageSize();
    }

    private static Map<String, Object> resourcesOf(MiddlewareCreateRequestDTO req) {
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("requests", nonNullMap("cpu", req.getCpuRequest(), "memory", req.getMemoryRequest()));
        resources.put("limits", nonNullMap("cpu", req.getCpuLimit(), "memory", req.getMemoryLimit()));
        return resources;
    }

    private GenericKubernetesResource resource(MiddlewareCreateRequestDTO req, String kind, String name,
                                               Map<String, Object> spec) {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setApiVersion(GROUP + "/" + VERSION);
        res.setKind(kind);
        ObjectMeta meta = new ObjectMeta();
        meta.setName(name);
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
