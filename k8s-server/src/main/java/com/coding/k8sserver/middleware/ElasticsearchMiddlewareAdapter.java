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
 * Elasticsearch 适配器（ECK，`elasticsearch.k8s.elastic.co/v1`）。
 *
 * <p>用一个 nodeSet（name=default）承载全部节点：`count` 控制节点数，
 * `volumeClaimTemplates` 控制每节点的数据盘。`node.store.allow_mmap: false` 是为了不要求宿主机调
 * `vm.max_mmap_count`（官方 sample 同款取舍，代价是性能略降）。
 *
 * <p><b>凭据</b>：ECK 默认开启安全，生成 `elastic` 用户密码到 {@code <name>-es-elastic-user} Secret，
 * 平台不写口令 Secret。版本必须落在 ECK 3.5 支持的范围内，否则 ECK 会拒绝而不是降级。
 */
@Component
public class ElasticsearchMiddlewareAdapter implements MiddlewareAdapter {

    private static final String GROUP = "elasticsearch.k8s.elastic.co";
    private static final String VERSION = "v1";
    private static final String DEFAULT_ES_VERSION = "9.5.0";

    static final CustomResourceDefinitionContext CRD = new CustomResourceDefinitionContext.Builder()
            .withGroup(GROUP).withVersion(VERSION).withKind("Elasticsearch")
            .withPlural("elasticsearches").withScope("Namespaced").build();

    @Override
    public String code() {
        return "elasticsearch";
    }

    @Override
    public boolean usesPlatformPassword() {
        return false;
    }

    @Override
    public List<CrSpec> buildCreate(MiddlewareCreateRequestDTO req) {
        int count = Math.max(1, req.getReplicas() == null ? 1 : req.getReplicas());
        String version = req.getVersion() == null || req.getVersion().isBlank()
                ? DEFAULT_ES_VERSION : req.getVersion();

        Map<String, Object> claimSpec = new LinkedHashMap<>();
        claimSpec.put("accessModes", List.of("ReadWriteOnce"));
        claimSpec.put("resources", Map.of("requests", Map.of("storage",
                req.getStorageSize() == null || req.getStorageSize().isBlank() ? "10Gi" : req.getStorageSize())));
        if (req.getStorageClass() != null && !req.getStorageClass().isBlank()) {
            claimSpec.put("storageClassName", req.getStorageClass());
        }

        // 可变结构：applyScale 会在原对象上改 count / resources
        Map<String, Object> container = new LinkedHashMap<>();
        container.put("name", "elasticsearch");
        container.put("resources", resourcesOf(req));
        Map<String, Object> podSpec = new LinkedHashMap<>();
        podSpec.put("containers", new java.util.ArrayList<>(List.of(container)));
        Map<String, Object> podTemplate = new LinkedHashMap<>();
        podTemplate.put("spec", podSpec);

        Map<String, Object> claim = new LinkedHashMap<>();
        claim.put("metadata", Map.of("name", "elasticsearch-data"));
        claim.put("spec", claimSpec);

        Map<String, Object> nodeSet = new LinkedHashMap<>();
        nodeSet.put("name", "default");
        nodeSet.put("count", count);
        nodeSet.put("config", Map.of("node.store.allow_mmap", false));
        nodeSet.put("podTemplate", podTemplate);
        nodeSet.put("volumeClaimTemplates", new java.util.ArrayList<>(List.of(claim)));

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("version", version);
        spec.put("nodeSets", new java.util.ArrayList<>(List.of(nodeSet)));
        return List.of(new CrSpec(CRD, resource(req, "Elasticsearch", spec)));
    }

    @Override
    public List<CustomResourceDefinitionContext> contexts() {
        return List.of(CRD);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void applyScale(GenericKubernetesResource live, CustomResourceDefinitionContext context,
                           MiddlewareScaleRequestDTO req) {
        Map<String, Object> spec = specOf(live);
        Object nodeSetsObj = spec.get("nodeSets");
        if (!(nodeSetsObj instanceof List<?> list) || list.isEmpty() || !(list.get(0) instanceof Map<?, ?>)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "实例规格不可识别");
        }
        Map<String, Object> nodeSet = (Map<String, Object>) list.get(0);
        if (req.getReplicas() != null) {
            nodeSet.put("count", Math.max(1, req.getReplicas()));
        }
        Object podTemplateObj = nodeSet.get("podTemplate");
        if (podTemplateObj instanceof Map<?, ?> podTemplate) {
            Object specObj = ((Map<String, Object>) podTemplate).get("spec");
            if (specObj instanceof Map<?, ?> podSpec) {
                Object containersObj = ((Map<String, Object>) podSpec).get("containers");
                if (containersObj instanceof List<?> containers && !containers.isEmpty()) {
                    ((Map<String, Object>) containers.get(0)).put("resources", resourcesOf(req));
                }
            }
        }
    }

    @Override
    public String statusSelector(String name, String kind) {
        return "elasticsearch.k8s.elastic.co/cluster-name=" + name;
    }

    @Override
    public Integer desiredPods(GenericKubernetesResource live, String kind) {
        Object nodeSets = specOf(live).get("nodeSets");
        if (!(nodeSets instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        int sum = 0;
        for (Object ns : list) {
            if (ns instanceof Map<?, ?> m && m.get("count") instanceof Number n) {
                sum += n.intValue();
            }
        }
        return sum > 0 ? sum : null;
    }

    @Override
    public String pvcSelector(String name, String instanceId) {
        return "elasticsearch.k8s.elastic.co/cluster-name=" + name;
    }

    // ==================== 内部 ====================

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
