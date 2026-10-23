package com.coding.k8sserver.middleware;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;
import com.coding.common.models.middleware.MiddlewareScaleRequestDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClient;
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

    /** ES 官方镜像前缀（initContainer 装插件用，必须与实例同版本） */
    private static final String ES_IMAGE_PREFIX = "docker.elastic.co/elasticsearch/elasticsearch:";
    /** IK 官方分发地址（infinilabs），路径末尾拼 ES 版本 */
    static final String IK_DOWNLOAD_PREFIX = "https://get.infini.cloud/elasticsearch/analysis-ik/";
    static final String PLUGINS_VOLUME = "elasticsearch-plugins";
    /** initContainer 也要写资源：命名空间有 limits 配额时，没资源的容器会被直接拒 */
    static final Map<String, Object> INIT_CONTAINER_RESOURCES = Map.of(
            "requests", Map.of("cpu", "100m", "memory", "128Mi"),
            "limits", Map.of("cpu", "500m", "memory", "512Mi"));

    /**
     * 幂等地把 IK 的 initContainer + 共享 plugins 卷挂到 podSpec（创建和后续编辑共用）。
     *
     * <p>IK 不随官方镜像发布：initContainer 把 zip 下载解到 emptyDir，ES 容器挂同一个卷。
     * ⚠️ 不能用 {@code bin/elasticsearch-plugin install}：它会对 config 目录 chown，而 ECK 默认
     * runAsNonRoot + drop ALL，实测报 Operation not permitted。
     *
     * @return true = 本次新加了；false = 已经装过（编辑时重复提交不会重复加）
     */
    @SuppressWarnings("unchecked")
    private static boolean ensureIk(Map<String, Object> podSpec, Map<String, Object> esContainer, String version) {
        List<Object> inits = podSpec.get("initContainers") instanceof List<?> list
                ? new java.util.ArrayList<>((List<Object>) list) : new java.util.ArrayList<>();
        boolean installed = inits.stream().anyMatch(o ->
                o instanceof Map<?, ?> m && "install-ik".equals(m.get("name")));
        if (installed) {
            return false;
        }
        Map<String, Object> pluginsMount = Map.of("name", PLUGINS_VOLUME,
                "mountPath", "/usr/share/elasticsearch/plugins");
        Map<String, Object> init = new LinkedHashMap<>();
        init.put("name", "install-ik");
        init.put("image", ES_IMAGE_PREFIX + version);
        init.put("command", List.of("sh", "-c",
                "set -e; cd /usr/share/elasticsearch/plugins; "
                        + "curl -fsSL --retry 3 -o ik.zip " + IK_DOWNLOAD_PREFIX + version + "; "
                        + "mkdir -p analysis-ik; unzip -q -o ik.zip -d analysis-ik; rm -f ik.zip"));
        init.put("resources", INIT_CONTAINER_RESOURCES);
        init.put("volumeMounts", new java.util.ArrayList<>(List.of(pluginsMount)));
        inits.add(init);
        podSpec.put("initContainers", inits);

        List<Object> volumes = podSpec.get("volumes") instanceof List<?> list
                ? new java.util.ArrayList<>((List<Object>) list) : new java.util.ArrayList<>();
        volumes.add(Map.of("name", PLUGINS_VOLUME, "emptyDir", Map.of()));
        podSpec.put("volumes", volumes);

        List<Object> mounts = esContainer.get("volumeMounts") instanceof List<?> list
                ? new java.util.ArrayList<>((List<Object>) list) : new java.util.ArrayList<>();
        mounts.add(pluginsMount);
        esContainer.put("volumeMounts", mounts);
        return true;
    }

    /**
     * CR 里是否装了 IK：看 nodeSets[].podTemplate.spec.initContainers 有没有 install-ik。
     *
     * <p>详情页回显「已安装」以此为准 —— 台账 spec_json 只有编辑补装成功时才回写，
     * 创建时就勾了 IK 的实例（以及补丁前建的实例）库里没有标记。
     */
    @Override
    public Boolean ikInstalled(GenericKubernetesResource live) {
        if (live == null) {
            return null;   // CR 没读到：交给台账兜底，别谎报「未安装」
        }
        Object spec = live.getAdditionalProperties().get("spec");
        Object nodeSets = spec instanceof Map<?, ?> m ? m.get("nodeSets") : null;
        if (!(nodeSets instanceof List<?> list)) {
            return false;
        }
        for (Object ns : list) {
            if (!(ns instanceof Map<?, ?> nodeSet)
                    || !(nodeSet.get("podTemplate") instanceof Map<?, ?> podTemplate)
                    || !(podTemplate.get("spec") instanceof Map<?, ?> podSpec)
                    || !(podSpec.get("initContainers") instanceof List<?> inits)) {
                continue;
            }
            if (inits.stream().anyMatch(o -> o instanceof Map<?, ?> c && "install-ik".equals(c.get("name")))) {
                return true;
            }
        }
        return false;
    }

    /** 关掉自签证书 = 该层用明文 HTTP */
    private static Map<String, Object> httpWithoutTls() {
        return Map.of("tls", Map.of("selfSignedCertificate", Map.of("disabled", true)));
    }

    private static boolean tlsEnabled(MiddlewareCreateRequestDTO req) {
        return !Boolean.FALSE.equals(req.getTls());
    }

    /** Kibana 侧车没有用户可调的资源入口，给一组固定值（够跑起来、也能过 limits 配额） */
    static final Map<String, Object> KIBANA_RESOURCES = Map.of(
            "requests", Map.of("cpu", "200m", "memory", "512Mi"),
            "limits", Map.of("cpu", "1", "memory", "1Gi"));

    static final CustomResourceDefinitionContext CRD = new CustomResourceDefinitionContext.Builder()
            .withGroup(GROUP).withVersion(VERSION).withKind("Elasticsearch")
            .withPlural("elasticsearches").withScope("Namespaced").build();

    /**
     * Kibana 与 ES 同名（跨 CRD 不冲突）：删除/状态都按实例名逐个 context 找，
     * 用 {@code <name>-kb} 这类派生名反而要额外记一份映射。
     */
    static final CustomResourceDefinitionContext KIBANA = new CustomResourceDefinitionContext.Builder()
            .withGroup("kibana.k8s.elastic.co").withVersion(VERSION).withKind("Kibana")
            .withPlural("kibanas").withScope("Namespaced").build();

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
        if (Boolean.TRUE.equals(req.getIk())) {
            ensureIk(podSpec, container, version);
        }
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
        if (!tlsEnabled(req)) {
            // 关掉自签证书 = HTTP 明文（ECS HTTP 层的 TLS 由 selfSignedCertificate 控制）
            spec.put("http", httpWithoutTls());
        }
        List<CrSpec> out = new java.util.ArrayList<>();
        out.add(new CrSpec(CRD, resource(req, GROUP + "/" + VERSION, "Elasticsearch", spec)));

        if (Boolean.TRUE.equals(req.getKibana())) {
            Map<String, Object> kibana = new LinkedHashMap<>();
            kibana.put("version", version);
            kibana.put("count", 1);
            kibana.put("elasticsearchRef", Map.of("name", req.getName()));
            if (!tlsEnabled(req)) {
                kibana.put("http", httpWithoutTls());   // Kibana 跟着 ES 一起走 http/https
            }
            // 必须带资源：命名空间有 limits.* 配额时，没写 requests/limits 的容器会被 quota 直接拒掉
            //（真集群验证：Kibana Pod 一直 Pending，事件是 must specify limits.cpu for: kibana）
            Map<String, Object> kibanaContainer = new LinkedHashMap<>();
            kibanaContainer.put("name", "kibana");
            kibanaContainer.put("resources", KIBANA_RESOURCES);
            kibana.put("podTemplate", Map.of("spec", Map.of("containers",
                    new java.util.ArrayList<>(List.of(kibanaContainer)))));
            out.add(new CrSpec(KIBANA, resource(req, "kibana.k8s.elastic.co/" + VERSION, "Kibana", kibana)));
        }
        return out;
    }

    @Override
    public List<CustomResourceDefinitionContext> contexts() {
        return List.of(CRD, KIBANA);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void applyScale(GenericKubernetesResource live, CustomResourceDefinitionContext context,
                           MiddlewareScaleRequestDTO req) {
        if ("Kibana".equals(context.getKind())) {
            return;   // Kibana 固定单副本，不随 ES 的节点数变化
        }
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
                    Map<String, Object> esContainer = (Map<String, Object>) containers.get(0);
                    // 只带 ik 的请求不能把资源字段抹掉
                    if (req.getCpuRequest() != null || req.getCpuLimit() != null
                            || req.getMemoryRequest() != null || req.getMemoryLimit() != null) {
                        esContainer.put("resources", resourcesOf(req));
                    }
                    if (Boolean.TRUE.equals(req.getIk())) {
                        Object version = spec.get("version");
                        ensureIk((Map<String, Object>) podSpec, esContainer,
                                version == null ? DEFAULT_ES_VERSION : String.valueOf(version));
                    }
                }
            }
        }
    }

    /** Kibana 是可选附属：ES 全绿但 Kibana 起不来时，实例状态要降级而不是报「运行中」。 */
    @Override
    public String accessoryProblem(KubernetesClient client, String namespace, String name) {
        try {
            return kibanaProblem(client.genericKubernetesResources(KIBANA)
                    .inNamespace(namespace).withName(name).get());
        } catch (RuntimeException e) {
            return null;   // 读不到就当没有 Kibana，不影响主实例判定
        }
    }

    /** 纯函数：从 Kibana CR 的 status 里读出问题（null = 没问题 / 没部署）。 */
    static String kibanaProblem(GenericKubernetesResource kibana) {
        if (kibana == null) {
            return null;
        }
        Object status = kibana.getAdditionalProperties().get("status");
        if (!(status instanceof Map<?, ?> st) || st.get("health") == null) {
            return "Kibana 尚未就绪";
        }
        if ("green".equals(st.get("health"))) {
            return null;
        }
        if (st.get("conditions") instanceof List<?> conditions) {
            for (Object c : conditions) {
                if (c instanceof Map<?, ?> cm && "False".equals(cm.get("status"))) {
                    Object msg = cm.get("message");
                    if (msg != null && !String.valueOf(msg).isBlank()) {
                        return "Kibana 异常：" + msg;
                    }
                }
            }
        }
        return "Kibana 健康状况 " + st.get("health");
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

    private GenericKubernetesResource resource(MiddlewareCreateRequestDTO req, String apiVersion, String kind,
                                               Map<String, Object> spec) {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setApiVersion(apiVersion);
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
