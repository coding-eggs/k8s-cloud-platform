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
    void selectorsUseEckLabels() {
        assertThat(adapter.statusSelector("mw-es-x", "Elasticsearch"))
                .isEqualTo("elasticsearch.k8s.elastic.co/cluster-name=mw-es-x");
        assertThat(adapter.pvcSelector("mw-es-x", "mwi_1"))
                .isEqualTo("elasticsearch.k8s.elastic.co/cluster-name=mw-es-x");
    }
}
