package com.coding.k8sserver.middleware;

import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;
import com.coding.common.models.middleware.MiddlewareScaleRequestDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ElasticsearchMiddlewareAdapterTest {

    private final ElasticsearchMiddlewareAdapter adapter = new ElasticsearchMiddlewareAdapter();

    private MiddlewareCreateRequestDTO req(int replicas) {
        MiddlewareCreateRequestDTO r = new MiddlewareCreateRequestDTO();
        r.setInstanceId("mwi_1");
        r.setClusterId("c1");
        r.setCode("elasticsearch");
        r.setMode("CLUSTER");
        r.setNamespace("demo");
        r.setName("mw-es-abc12345");
        r.setReplicas(replicas);
        r.setCpuRequest("500m");
        r.setCpuLimit("2");
        r.setMemoryRequest("2Gi");
        r.setMemoryLimit("4Gi");
        r.setStorageSize("30Gi");
        return r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> spec(GenericKubernetesResource res) {
        return (Map<String, Object>) res.getAdditionalProperties().get("spec");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nodeSet(GenericKubernetesResource res) {
        return ((List<Map<String, Object>>) spec(res).get("nodeSets")).get(0);
    }

    @Test
    void buildsSingleNodeSetWithVersionAndStorage() {
        var res = adapter.buildCreate(req(3)).get(0).resource();

        assertThat(res.getApiVersion()).isEqualTo("elasticsearch.k8s.elastic.co/v1");
        assertThat(res.getKind()).isEqualTo("Elasticsearch");
        assertThat(spec(res)).containsEntry("version", "9.5.0");
        assertThat(nodeSet(res)).containsEntry("count", 3);
        @SuppressWarnings("unchecked")
        var claim = (Map<String, Object>) ((List<Map<String, Object>>) nodeSet(res).get("volumeClaimTemplates")).get(0);
        assertThat(((Map<String, Object>) claim.get("spec")).get("accessModes")).isEqualTo(List.of("ReadWriteOnce"));
    }

    @Test
    void explicitVersionIsHonoured() {
        var r = req(1);
        r.setVersion("9.4.2");

        assertThat(spec(adapter.buildCreate(r).get(0).resource())).containsEntry("version", "9.4.2");
    }

    @Test
    void mmapIsDisabledToAvoidHostTuning() {
        @SuppressWarnings("unchecked")
        var config = (Map<String, Object>) nodeSet(adapter.buildCreate(req(1)).get(0).resource()).get("config");
        assertThat(config).containsEntry("node.store.allow_mmap", false);
    }

    @Test
    void scaleChangesCountAndResources() {
        var live = adapter.buildCreate(req(1)).get(0).resource();
        var scale = new MiddlewareScaleRequestDTO();
        scale.setReplicas(3);
        scale.setCpuRequest("1");
        scale.setMemoryRequest("4Gi");

        adapter.applyScale(live, ElasticsearchMiddlewareAdapter.CRD, scale);

        assertThat(nodeSet(live)).containsEntry("count", 3);
        @SuppressWarnings("unchecked")
        var containers = (List<Map<String, Object>>) ((Map<String, Object>) ((Map<String, Object>)
                nodeSet(live).get("podTemplate")).get("spec")).get("containers");
        assertThat((Map<String, Object>) containers.get(0).get("resources")).isNotNull();
    }

    @Test
    void usesEckGeneratedCredentials() {
        assertThat(adapter.usesPlatformPassword()).isFalse();
    }

    @Test
    void desiredPodsFollowNodeSetCount() {
        var res = adapter.buildCreate(req(3)).get(0).resource();

        assertThat(adapter.desiredPods(res, "Elasticsearch")).isEqualTo(3);
    }

    @Test
    void kibanaToggleAddsASecondCrPointingAtTheInstance() {
        var r = req(1);
        r.setKibana(true);

        var out = adapter.buildCreate(r);

        assertThat(out).extracting(c -> c.resource().getKind()).containsExactly("Elasticsearch", "Kibana");
        var kibana = out.get(1).resource();
        assertThat(kibana.getApiVersion()).isEqualTo("kibana.k8s.elastic.co/v1");
        // 与 ES 同名：删除/状态都按实例名逐个 context 找，不需要额外映射
        assertThat(kibana.getMetadata().getName()).isEqualTo("mw-es-abc12345");
        assertThat(spec(kibana)).containsEntry("count", 1);
        @SuppressWarnings("unchecked")
        var ref = (Map<String, Object>) spec(kibana).get("elasticsearchRef");
        assertThat(ref).containsEntry("name", "mw-es-abc12345");
        assertThat(spec(kibana)).containsEntry("version", "9.5.0");
        // 命名空间有 limits 配额时，Kibana 容器没资源字段会被 quota 拒（真集群验证：一直 Pending）
        @SuppressWarnings("unchecked")
        var podSpec = (Map<String, Object>) spec(kibana).get("podTemplate");
        @SuppressWarnings("unchecked")
        var containers = (List<Map<String, Object>>) ((Map<String, Object>) podSpec.get("spec")).get("containers");
        assertThat((Map<String, Object>) containers.get(0).get("resources")).containsKeys("requests", "limits");
    }

    @Test
    void kibanaIsOffByDefaultAndScaleLeavesItAlone() {
        assertThat(adapter.buildCreate(req(1))).hasSize(1);

        var live = adapter.buildCreate(req(3)).get(0).resource();
        var scale = new MiddlewareScaleRequestDTO();
        scale.setMode("CLUSTER");
        scale.setReplicas(5);
        // Kibana context 也在 contexts() 里：扩容时对它必须是 no-op，否则会把 ES 的 nodeSets 逻辑套上去
        adapter.applyScale(live, ElasticsearchMiddlewareAdapter.KIBANA, scale);
        assertThat(spec(live)).doesNotContainKey("count");
    }

    @Test
    void contextsCoverElasticsearchAndKibana() {
        assertThat(adapter.contexts()).extracting(c -> c.getKind())
                .containsExactly("Elasticsearch", "Kibana");
    }

    @Test
    void kibanaProblemIsNullWhenGreenOrAbsent() {
        assertThat(ElasticsearchMiddlewareAdapter.kibanaProblem(null)).isNull();

        var kibana = new GenericKubernetesResource();
        kibana.setAdditionalProperty("status", Map.of("health", "green"));
        assertThat(ElasticsearchMiddlewareAdapter.kibanaProblem(kibana)).isNull();
    }

    @Test
    void kibanaProblemSurfacesConditionMessage() {
        // ES 全绿但 Kibana 起不来时，实例状态要降级并说清原因
        var kibana = new GenericKubernetesResource();
        kibana.setAdditionalProperty("status", Map.of(
                "health", "red",
                "conditions", List.of(Map.of(
                        "type", "Ready", "status", "False",
                        "message", "Cannot connect to Elasticsearch"))));

        assertThat(ElasticsearchMiddlewareAdapter.kibanaProblem(kibana))
                .contains("Kibana 异常").contains("Cannot connect to Elasticsearch");
    }

    @Test
    void kibanaProblemFallsBackToHealthWhenNoMessage() {
        var kibana = new GenericKubernetesResource();
        kibana.setAdditionalProperty("status", Map.of("health", "yellow"));

        assertThat(ElasticsearchMiddlewareAdapter.kibanaProblem(kibana)).contains("yellow");
    }

    @Test
    void tlsIsOnByDefault() {
        var res = adapter.buildCreate(req(1)).get(0).resource();

        assertThat(spec(res)).doesNotContainKey("http");
    }

    @Test
    void tlsCanBeDisabledForBothElasticsearchAndKibana() {
        var r = req(1);
        r.setTls(false);
        r.setKibana(true);

        var out = adapter.buildCreate(r);

        @SuppressWarnings("unchecked")
        var esHttp = (Map<String, Object>) spec(out.get(0).resource()).get("http");
        assertThat(esHttp.toString()).contains("selfSignedCertificate").contains("disabled=true");
        @SuppressWarnings("unchecked")
        var kbHttp = (Map<String, Object>) spec(out.get(1).resource()).get("http");
        assertThat(kbHttp.toString()).contains("selfSignedCertificate").contains("disabled=true");
    }

    @Test
    void ikInstallsViaInitContainerIntoASharedPluginsVolume() {
        var r = req(1);
        r.setIk(true);

        var res = adapter.buildCreate(r).get(0).resource();
        @SuppressWarnings("unchecked")
        var podSpec = (Map<String, Object>) ((Map<String, Object>) ((List<?>) spec(res).get("nodeSets")).get(0)
                instanceof Map<?, ?> ns ? ((Map<String, Object>) ns).get("podTemplate") : null);
        @SuppressWarnings("unchecked")
        var pod = (Map<String, Object>) podSpec.get("spec");

        @SuppressWarnings("unchecked")
        var inits = (List<Map<String, Object>>) pod.get("initContainers");
        assertThat(inits).hasSize(1);
        assertThat(inits.get(0)).containsEntry("name", "install-ik");
        assertThat(inits.get(0).get("image")).isEqualTo("docker.elastic.co/elasticsearch/elasticsearch:9.5.0");
        assertThat((String) inits.get(0).get("command").toString())
                .contains("get.infini.cloud/elasticsearch/analysis-ik/9.5.0");
        assertThat(inits.get(0).get("resources")).isNotNull();   // 配额要求 init 容器也有资源
        assertThat((List<?>) pod.get("volumes")).hasSize(1);
        @SuppressWarnings("unchecked")
        var containers = (List<Map<String, Object>>) pod.get("containers");
        assertThat(containers.get(0).get("volumeMounts")).isNotNull();
    }

    @Test
    void ikIsOffByDefault() {
        @SuppressWarnings("unchecked")
        var nodeSet = (Map<String, Object>) ((List<?>) spec(adapter.buildCreate(req(1)).get(0).resource())
                .get("nodeSets")).get(0);
        @SuppressWarnings("unchecked")
        var pod = (Map<String, Object>) ((Map<String, Object>) nodeSet.get("podTemplate")).get("spec");
        assertThat(pod).doesNotContainKey("initContainers");
    }

    @Test
    void editingCanInstallIkOnAnExistingInstanceIdempotently() {
        var live = adapter.buildCreate(req(1)).get(0).resource();   // 创建时没勾 ik

        var scale = new MiddlewareScaleRequestDTO();
        scale.setMode("CLUSTER");
        scale.setIk(true);
        adapter.applyScale(live, ElasticsearchMiddlewareAdapter.CRD, scale);

        @SuppressWarnings("unchecked")
        var nodeSet = (Map<String, Object>) ((List<?>) spec(live).get("nodeSets")).get(0);
        @SuppressWarnings("unchecked")
        var pod = (Map<String, Object>) ((Map<String, Object>) nodeSet.get("podTemplate")).get("spec");
        @SuppressWarnings("unchecked")
        var inits = (List<Map<String, Object>>) pod.get("initContainers");
        assertThat(inits).hasSize(1);
        assertThat(inits.get(0)).containsEntry("name", "install-ik");
        // 资源没带时不能被抹掉
        @SuppressWarnings("unchecked")
        var resources = (Map<String, Object>) ((List<Map<String, Object>>) pod.get("containers")).get(0).get("resources");
        assertThat(resources).containsKeys("requests", "limits");

        // 再提交一次（编辑弹窗重复保存）不会加第二个 initContainer
        adapter.applyScale(live, ElasticsearchMiddlewareAdapter.CRD, scale);
        @SuppressWarnings("unchecked")
        var again = (List<Map<String, Object>>) pod.get("initContainers");
        assertThat(again).hasSize(1);
    }

    @Test
    void reportsIkStateFromTheLiveCr() {
        // 详情页「已安装」按 CR 现读：台账 spec_json 只有编辑补装时才回写，
        // 创建时就勾了 IK 的老实例库里没有标记 —— 只看 DB 会把已安装显示成未安装
        var withIk = req(1);
        withIk.setIk(true);
        assertThat(adapter.ikInstalled(adapter.buildCreate(withIk).get(0).resource())).isTrue();
        assertThat(adapter.ikInstalled(adapter.buildCreate(req(1)).get(0).resource())).isFalse();
        // CR 没读到（集群不可用）时不知道装没装，交给台账的 spec_json 兜底
        assertThat(adapter.ikInstalled(null)).isNull();
    }

    @Test
    void editingWithoutIkFlagLeavesThePodTemplateAlone() {
        var live = adapter.buildCreate(req(1)).get(0).resource();
        var scale = new MiddlewareScaleRequestDTO();
        scale.setMode("CLUSTER");
        scale.setReplicas(3);

        adapter.applyScale(live, ElasticsearchMiddlewareAdapter.CRD, scale);

        @SuppressWarnings("unchecked")
        var nodeSet = (Map<String, Object>) ((List<?>) spec(live).get("nodeSets")).get(0);
        @SuppressWarnings("unchecked")
        var pod = (Map<String, Object>) ((Map<String, Object>) nodeSet.get("podTemplate")).get("spec");
        assertThat(pod).doesNotContainKey("initContainers");
    }

    @Test
    void selectorsUseEckLabels() {
        assertThat(adapter.statusSelector("mw-es-x", "Elasticsearch"))
                .isEqualTo("elasticsearch.k8s.elastic.co/cluster-name=mw-es-x");
        assertThat(adapter.pvcSelector("mw-es-x", "mwi_1"))
                .isEqualTo("elasticsearch.k8s.elastic.co/cluster-name=mw-es-x");
    }
}
