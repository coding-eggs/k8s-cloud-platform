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
 * Milvus 适配器（milvus-operator，`milvus.io/v1beta1`）。
 *
 * <p>数据全部在外部对象存储里：CR 只声明 `dependencies.storage.external`，平台绝不在集群里拉起 minio/silo。
 * etcd 由 Operator 在租户命名空间内拉起（1~3 节点）。
 *
 * <p><b>两个必须成对写的开关</b>：`dependencies.storage.ssl.enabled` 供 operator 做 StorageReady 探测，
 * `config.minio.useSSL` 才是 Milvus 客户端的开关 —— 只写一个会"探测说通、实例连不上"。
 */
@Component
public class MilvusMiddlewareAdapter implements MiddlewareAdapter {

    static final String GROUP = "milvus.io";
    static final String VERSION = "v1beta1";
    static final String DEFAULT_VERSION = "2.6.25";
    static final String IMAGE_PREFIX = "milvusdb/milvus:v";
    static final String STORAGE_SECRET_SUFFIX = "-milvus-storage";
    static final int METRICS_PORT = 9091;
    static final String ETCD_STORAGE = "5Gi";
    static final String INSTANCE_LABEL = "platform.coding/middleware-instance";
    static final Map<String, Object> ETCD_RESOURCES = Map.of(
            "requests", Map.of("cpu", "100m", "memory", "256Mi"),
            "limits", Map.of("cpu", "500m", "memory", "512Mi"));

    static final CustomResourceDefinitionContext CRD = new CustomResourceDefinitionContext.Builder()
            .withGroup(GROUP).withVersion(VERSION).withKind("Milvus")
            .withPlural("milvuses").withScope("Namespaced").build();

    @Override
    public String code() {
        return "milvus";
    }

    /** Milvus 默认不开鉴权：平台不生成口令 Secret。 */
    @Override
    public boolean usesPlatformPassword() {
        return false;
    }

    @Override
    public String extraSecretName(String name) {
        return name + STORAGE_SECRET_SUFFIX;
    }

    @Override
    public Map<String, String> extraSecretData(MiddlewareCreateRequestDTO req) {
        if (isBlank(req.getObjectStorageAccessKey()) || isBlank(req.getObjectStorageSecretKey())) {
            return Map.of();
        }
        // 键名是 operator 的常量 AccessKey / SecretKey
        return Map.of("accesskey", req.getObjectStorageAccessKey(),
                "secretkey", req.getObjectStorageSecretKey());
    }

    @Override
    public List<CrSpec> buildCreate(MiddlewareCreateRequestDTO req) {
        if (isBlank(req.getObjectStorageEndpoint()) || isBlank(req.getObjectStorageBucket())) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "缺少对象存储参数（endpoint / bucket）");
        }
        boolean cluster = "CLUSTER".equals(req.getMode());
        boolean ssl = Boolean.TRUE.equals(req.getObjectStorageUseSsl());

        Map<String, Object> components = new LinkedHashMap<>();
        components.put("image", IMAGE_PREFIX + versionOf(req));
        components.put("resources", resourcesOf(req));
        if (cluster) {
            Map<String, Object> queryNode = new LinkedHashMap<>();
            queryNode.put("replicas", Math.max(1, req.getReplicas() == null ? 1 : req.getReplicas()));
            components.put("queryNode", queryNode);
        }

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("mode", cluster ? "cluster" : "standalone");
        spec.put("components", components);
        if (!cluster) {
            spec.put("persistence", persistenceOf(req));
        }
        spec.put("dependencies", dependenciesOf(req, cluster));
        // spec.config 直接并进 milvus.yaml：minio 是顶层键，**不要**再包一层 milvus
        spec.put("config", Map.of("minio", Map.of(
                "bucketName", req.getObjectStorageBucket(),
                "useVirtualHost", false,   // SeaweedFS 只支持路径风格
                "useSSL", ssl)));

        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setApiVersion(GROUP + "/" + VERSION);
        res.setKind("Milvus");
        ObjectMeta meta = new ObjectMeta();
        meta.setName(req.getName());
        meta.setNamespace(req.getNamespace());
        meta.setLabels(Map.of(
                "platform.coding/managed-by", "middleware",
                INSTANCE_LABEL, instanceLabel(req)));
        res.setMetadata(meta);
        res.setAdditionalProperty("spec", spec);
        return List.of(new CrSpec(CRD, res));
    }

    @Override
    public List<CustomResourceDefinitionContext> contexts() {
        return List.of(CRD);
    }

    @Override
    public void applyScale(GenericKubernetesResource live, CustomResourceDefinitionContext context,
                           MiddlewareScaleRequestDTO req) {
        boolean cluster = "CLUSTER".equals(req.getMode());
        if (!cluster && req.getReplicas() != null && req.getReplicas() != 1) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "单节点形态固定 1 个节点，要扩展请删除后按「集群」重建");
        }
        Map<String, Object> spec = specOf(live);
        Map<String, Object> components = asMap(spec.get("components"));
        if (notBlank(req.getCpuRequest()) || notBlank(req.getCpuLimit())
                || notBlank(req.getMemoryRequest()) || notBlank(req.getMemoryLimit())) {
            components.put("resources", resourcesOf(req));
        }
        if (cluster && req.getReplicas() != null) {
            Map<String, Object> queryNode = asMap(components.get("queryNode"));
            queryNode.put("replicas", Math.max(1, req.getReplicas()));
            components.put("queryNode", queryNode);
        }
        spec.put("components", components);
    }

    @Override
    public String statusSelector(String name, String kind) {
        return "app.kubernetes.io/instance=" + name + ",app.kubernetes.io/name=milvus";
    }

    @Override
    public MetricsSpec metricsSpec(String name, String kind, String instanceId) {
        return new MetricsSpec(METRICS_PORT, "/metrics", "http", statusSelector(name, kind));
    }

    @Override
    public Integer desiredPods(GenericKubernetesResource live, String kind) {
        Map<String, Object> spec = specOf(live);
        Object components = spec.get("components");
        if (!(components instanceof Map<?, ?> m)) {
            return null;
        }
        // 只数当前形态真正会起的组件：集群形态的 CR 里常带着 standalone.replicas=0，
        // 单节点形态也可能残留集群组件键 —— 一律按 mode 取白名单，别把别的形态的副本数算进来。
        boolean cluster = "cluster".equals(spec.get("mode"));
        List<String> keys = cluster
                ? List.of("proxy", "mixCoord", "rootCoord", "indexCoord", "dataCoord", "queryCoord",
                "dataNode", "queryNode", "streamingNode", "indexNode")
                : List.of("standalone");
        int sum = 0;
        boolean any = false;
        for (String key : keys) {
            Object component = m.get(key);
            if (component instanceof Map<?, ?> c && c.get("replicas") instanceof Number n) {
                int replicas = n.intValue();
                if (replicas < 0) {
                    continue;   // -1 = 交给 operator/HPA 管，不能从求和里减掉
                }
                any = true;
                sum += replicas;
            }
        }
        return any && sum > 0 ? sum : null;
    }

    /**
     * Milvus 的 condition 是 EtcdReady / StorageReady / MsgStreamReady / MilvusReady，
     * 共享的 crFailureMessage 只认 Ready/Available —— 这里按根因优先级取第一条非空消息。
     */
    @Override
    public String crProblem(GenericKubernetesResource live) {
        if (live == null) {
            return null;
        }
        Object status = live.getAdditionalProperties().get("status");
        if (!(status instanceof Map<?, ?> st)) {
            return null;
        }
        // 先看条件：StorageReady/EtcdReady 的 message 才是根因（桶配错、etcd 起不来）
        if (st.get("conditions") instanceof List<?> conditions) {
            for (String type : List.of("StorageReady", "EtcdReady", "MsgStreamReady", "MilvusReady")) {
                for (Object c : conditions) {
                    if (!(c instanceof Map<?, ?> cm) || !type.equals(cm.get("type"))) {
                        continue;
                    }
                    if (!"False".equals(cm.get("status"))) {
                        continue;
                    }
                    Object msg = cm.get("message");
                    if (msg != null && !String.valueOf(msg).isBlank()) {
                        return type + "：" + String.valueOf(msg).trim();
                    }
                }
            }
        }
        Object health = st.get("status");
        if ("Unhealthy".equals(health) || "Stopped".equals(health)) {
            return "Milvus 状态 " + health;
        }
        return null;
    }

    // ==================== 内部 ====================

    /** "http://host:8333/x" / "host:8333" → "host:8333"（operator 自己按 useSSL 补默认端口）。 */
    static String hostPort(String endpoint) {
        String s = endpoint == null ? "" : endpoint.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        return s;
    }

    static int etcdReplicas(MiddlewareCreateRequestDTO req) {
        Integer n = req.getEtcdReplicas();
        if (n == null) {
            return "CLUSTER".equals(req.getMode()) ? 3 : 1;
        }
        if (n < 1 || n > 3) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "etcd 节点数只能是 1~3");
        }
        return n;
    }

    private static Map<String, Object> persistenceOf(MiddlewareCreateRequestDTO req) {
        Map<String, Object> claimSpec = new LinkedHashMap<>();
        claimSpec.put("accessModes", List.of("ReadWriteOnce"));
        claimSpec.put("resources", Map.of("requests", Map.of("storage",
                isBlank(req.getLocalStorageSize()) ? "10Gi" : req.getLocalStorageSize())));
        if (!isBlank(req.getStorageClass())) {
            claimSpec.put("storageClassName", req.getStorageClass());
        }
        Map<String, Object> claim = new LinkedHashMap<>();
        // 平台标签：删实例（keepPvc=false）时按它回收这块 PVC —— operator 建的 PVC 没有 OwnerReference
        claim.put("labels", Map.of(INSTANCE_LABEL, instanceLabel(req)));
        claim.put("spec", claimSpec);

        Map<String, Object> persistence = new LinkedHashMap<>();
        persistence.put("enabled", true);
        persistence.put("persistentVolumeClaim", claim);
        return persistence;
    }

    private static Map<String, Object> dependenciesOf(MiddlewareCreateRequestDTO req, boolean cluster) {
        Map<String, Object> persistence = new LinkedHashMap<>();
        persistence.put("size", ETCD_STORAGE);
        persistence.put("labels", Map.of(INSTANCE_LABEL, instanceLabel(req)));
        if (!isBlank(req.getStorageClass())) {
            persistence.put("storageClass", req.getStorageClass());
        }
        Map<String, Object> etcdValues = new LinkedHashMap<>();
        etcdValues.put("replicaCount", etcdReplicas(req));
        etcdValues.put("persistence", persistence);
        etcdValues.put("resources", ETCD_RESOURCES);   // etcd chart 默认完全不写 resources，配额会拒

        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("external", true);
        storage.put("type", "S3");
        storage.put("endpoint", hostPort(req.getObjectStorageEndpoint()));
        storage.put("secretRef", req.getName() + STORAGE_SECRET_SUFFIX);
        storage.put("ssl", Map.of("enabled", Boolean.TRUE.equals(req.getObjectStorageUseSsl())));

        Map<String, Object> dependencies = new LinkedHashMap<>();
        if (cluster) {
            // 2.6 的 WAL 跑在 Milvus 进程内、直接写对象存储：不需要 PVC，也不拉 Pulsar/Kafka
            dependencies.put("msgStreamType", "woodpecker");
        }
        // CRD 默认 deletionPolicy=Retain：不改的话删 CR 后 etcd 的 Helm release/Pod 会留下来。
        // PVC 不交给 operator 级联删（pvcDeletion 保持默认 false）—— keepPvc 语义由平台按标签处理。
        Map<String, Object> inCluster = new LinkedHashMap<>();
        inCluster.put("deletionPolicy", "Delete");
        inCluster.put("values", etcdValues);
        dependencies.put("etcd", Map.of("inCluster", inCluster));
        dependencies.put("storage", storage);
        return dependencies;
    }

    private static Map<String, Object> resourcesOf(MiddlewareCreateRequestDTO req) {
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("requests", nonNullMap("cpu", req.getCpuRequest(), "memory", req.getMemoryRequest()));
        resources.put("limits", nonNullMap("cpu", req.getCpuLimit(), "memory", req.getMemoryLimit()));
        return resources;
    }

    private static Map<String, Object> resourcesOf(MiddlewareScaleRequestDTO req) {
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("requests", nonNullMap("cpu", req.getCpuRequest(), "memory", req.getMemoryRequest()));
        resources.put("limits", nonNullMap("cpu", req.getCpuLimit(), "memory", req.getMemoryLimit()));
        return resources;
    }

    private static Map<String, Object> nonNullMap(String k1, String v1, String k2, String v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!isBlank(v1)) {
            m.put(k1, v1);
        }
        if (!isBlank(v2)) {
            m.put(k2, v2);
        }
        return m;
    }

    private static String versionOf(MiddlewareCreateRequestDTO req) {
        return isBlank(req.getVersion()) ? DEFAULT_VERSION : req.getVersion().trim();
    }

    private static String instanceLabel(MiddlewareCreateRequestDTO req) {
        return req.getInstanceId() == null ? "-" : req.getInstanceId();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> specOf(GenericKubernetesResource res) {
        Object spec = res.getAdditionalProperties().get("spec");
        return spec instanceof Map ? (Map<String, Object>) spec : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
