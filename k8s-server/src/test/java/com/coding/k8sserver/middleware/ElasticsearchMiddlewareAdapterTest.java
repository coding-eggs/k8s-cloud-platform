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
    void selectorsUseEckLabels() {
        assertThat(adapter.statusSelector("mw-es-x"))
                .isEqualTo("elasticsearch.k8s.elastic.co/cluster-name=mw-es-x");
        assertThat(adapter.pvcSelector("mw-es-x", "mwi_1"))
                .isEqualTo("elasticsearch.k8s.elastic.co/cluster-name=mw-es-x");
    }
}
