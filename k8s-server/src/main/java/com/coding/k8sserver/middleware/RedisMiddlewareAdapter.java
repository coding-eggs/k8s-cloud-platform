package com.coding.k8sserver.middleware;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;
import com.coding.common.models.middleware.MiddlewareScaleRequestDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis 适配器（OT-Container-Kit，`redis.redis.opstreelabs.in/v1beta2`）。
 *
 * <p>三种形态对应三个 CR（哨兵是两个）：
 * <ul>
 *   <li>STANDALONE → {@code Redis}</li>
 *   <li>SENTINEL → {@code RedisReplication}（数据节点数）+ {@code RedisSentinel}（哨兵固定 3）</li>
 *   <li>CLUSTER → {@code RedisCluster}（clusterSize = 主分片数）</li>
 * </ul>
 *
 * <p>密码只以 Secret 引用形式出现在 spec 里（`kubernetesConfig.redisSecret`），明文由
 * {@link MiddlewareInstanceService} 写进 Secret。
 */
@Component
public class RedisMiddlewareAdapter implements MiddlewareAdapter {

    static final String GROUP = "redis.redis.opstreelabs.in";
    static final String VERSION = "v1beta2";
    static final String DEFAULT_IMAGE = "quay.io/opstree/redis:v7.0.15";

    private static final int SENTINEL_COUNT = 3;

    static final CustomResourceDefinitionContext REDIS = crd("Redis", "redis");
    static final CustomResourceDefinitionContext REPLICATION = crd("RedisReplication", "redisreplications");
    static final CustomResourceDefinitionContext SENTINEL = crd("RedisSentinel", "redissentinels");
    static final CustomResourceDefinitionContext CLUSTER = crd("RedisCluster", "redisclusters");

    @Override
    public String code() {
        return "redis";
    }

    @Override
    public List<CrSpec> buildCreate(MiddlewareCreateRequestDTO req) {
        return switch (req.getMode()) {
            case "STANDALONE" -> List.of(new CrSpec(REDIS, resource(req, "Redis", baseSpec(req))));
            case "SENTINEL" -> buildSentinel(req);
            case "CLUSTER" -> buildCluster(req);
            default -> throw new CloudPlatformException(EnumResponseType.ERROR,
                    "Redis 不支持的形态：" + req.getMode());
        };
    }

    private List<CrSpec> buildSentinel(MiddlewareCreateRequestDTO req) {
        Map<String, Object> repSpec = baseSpec(req);
        repSpec.put("clusterSize", Math.max(2, replicas(req)));
        GenericKubernetesResource replication = resource(req, "RedisReplication", repSpec);

        Map<String, Object> sentinelSpec = baseSpec(req);
        sentinelSpec.put("clusterSize", SENTINEL_COUNT);
        sentinelSpec.put("redisSentinelConfig", Map.of("redisReplicationName", req.getName()));
        GenericKubernetesResource sentinel = resource(req, "RedisSentinel", sentinelSpec);

        List<CrSpec> out = new ArrayList<>();
        out.add(new CrSpec(REPLICATION, replication));
        out.add(new CrSpec(SENTINEL, sentinel));
        return out;
    }

    private List<CrSpec> buildCluster(MiddlewareCreateRequestDTO req) {
        Map<String, Object> spec = baseSpec(req);
        spec.put("clusterSize", Math.max(3, replicas(req)));
        spec.put("clusterVersion", "v7");
        spec.put("persistenceEnabled", hasStorage(req));
        return List.of(new CrSpec(CLUSTER, resource(req, "RedisCluster", spec)));
    }

    /** 公共 spec：镜像、资源、密码 Secret 引用、持久化。 */
    private Map<String, Object> baseSpec(MiddlewareCreateRequestDTO req) {
        Map<String, Object> kc = new LinkedHashMap<>();
        kc.put("image", DEFAULT_IMAGE);
        kc.put("imagePullPolicy", "IfNotPresent");
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("requests", nonNullMap("cpu", req.getCpuRequest(), "memory", req.getMemoryRequest()));
        resources.put("limits", nonNullMap("cpu", req.getCpuLimit(), "memory", req.getMemoryLimit()));
        kc.put("resources", resources);
        kc.put("redisSecret", Map.of("name", secretName(req.getName()), "key", "password"));

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("kubernetesConfig", kc);
        spec.put("podSecurityContext", Map.of("runAsUser", 1000, "fsGroup", 1000));
        if (hasStorage(req)) {
            spec.put("storage", storageSpec(req));
        }
        return spec;
    }

    private Map<String, Object> storageSpec(MiddlewareCreateRequestDTO req) {
        Map<String, Object> claimSpec = new LinkedHashMap<>();
        claimSpec.put("accessModes", List.of("ReadWriteOnce"));
        claimSpec.put("resources", Map.of("requests", Map.of("storage", req.getStorageSize())));
        if (req.getStorageClass() != null && !req.getStorageClass().isBlank()) {
            claimSpec.put("storageClassName", req.getStorageClass());
        }
        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("keepAfterDelete", true);
        // PVC 必须带平台标签：删除实例时用它找 PVC（operator 自己的标签不保证存在）
        storage.put("volumeClaimTemplate", Map.of(
                "metadata", Map.of("labels", Map.of(
                        "platform.coding/managed-by", "middleware",
                        "platform.coding/middleware-instance",
                        req.getInstanceId() == null ? "-" : req.getInstanceId())),
                "spec", claimSpec));
        return storage;
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

    @Override
    public List<CustomResourceDefinitionContext> contexts() {
        return List.of(REDIS, REPLICATION, SENTINEL, CLUSTER);
    }

    @Override
    public void applyScale(GenericKubernetesResource live, CustomResourceDefinitionContext context,
                           MiddlewareScaleRequestDTO req) {
        Map<String, Object> spec = specOf(live);
        String kind = context.getKind();

        // 哨兵数固定 3：sentinel 侧永远不改 clusterSize，只允许改资源
        if ("RedisSentinel".equals(kind)) {
            updateResources(spec, req);
            return;
        }
        if ("RedisCluster".equals(kind) || "RedisReplication".equals(kind)) {
            if (req.getReplicas() == null) {
                throw new CloudPlatformException(EnumResponseType.ERROR, "缺少目标节点数");
            }
            int target = req.getReplicas();
            if ("RedisCluster".equals(kind)) {
                target = Math.max(3, target);
            } else {
                target = Math.max(2, target);
            }
            spec.put("clusterSize", target);
            updateResources(spec, req);
            return;
        }
        // 单节点形态：只能改资源
        if (req.getReplicas() != null && req.getReplicas() != 1) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "单节点形态不支持调整节点数，只能改资源");
        }
        updateResources(spec, req);
    }

    @SuppressWarnings("unchecked")
    private void updateResources(Map<String, Object> spec, MiddlewareScaleRequestDTO req) {
        Object kc = spec.get("kubernetesConfig");
        if (kc instanceof Map<?, ?> kcMap) {
            Map<String, Object> kcm = (Map<String, Object>) kcMap;
            Map<String, Object> resources = new LinkedHashMap<>();
            resources.put("requests", nonNullMap("cpu", req.getCpuRequest(), "memory", req.getMemoryRequest()));
            resources.put("limits", nonNullMap("cpu", req.getCpuLimit(), "memory", req.getMemoryLimit()));
            kcm.put("resources", resources);
        }
    }

    @Override
    public String statusSelector(String name) {
        // OT-Container-Kit 给实例 Pod 打的标签是 app=<CR 名>（真集群验证时确认）
        return "app=" + name;
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

    private static boolean hasStorage(MiddlewareCreateRequestDTO req) {
        return req.getStorageSize() != null && !req.getStorageSize().isBlank();
    }

    private static int replicas(MiddlewareCreateRequestDTO req) {
        return req.getReplicas() == null ? 1 : req.getReplicas();
    }

    static String secretName(String name) {
        return name + "-credentials";
    }

    private static CustomResourceDefinitionContext crd(String kind, String plural) {
        return new CustomResourceDefinitionContext.Builder()
                .withGroup(GROUP)
                .withVersion(VERSION)
                .withKind(kind)
                .withPlural(plural)
                .withScope("Namespaced")
                .build();
    }
}
