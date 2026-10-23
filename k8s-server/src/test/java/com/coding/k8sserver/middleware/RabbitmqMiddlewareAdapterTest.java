package com.coding.k8sserver.middleware;

import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RabbitmqMiddlewareAdapterTest {

    private final RabbitmqMiddlewareAdapter adapter = new RabbitmqMiddlewareAdapter();

    private MiddlewareCreateRequestDTO req(String mode, int replicas) {
        MiddlewareCreateRequestDTO r = new MiddlewareCreateRequestDTO();
        r.setInstanceId("mwi_1");
        r.setClusterId("c1");
        r.setCode("rabbitmq");
        r.setMode(mode);
        r.setNamespace("demo");
        r.setName("mw-rabbitmq-abc12345");
        r.setReplicas(replicas);
        r.setCpuRequest("300m");
        r.setCpuLimit("1");
        r.setMemoryRequest("512Mi");
        r.setMemoryLimit("1Gi");
        r.setStorageSize("10Gi");
        return r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> spec(GenericKubernetesResource res) {
        return (Map<String, Object>) res.getAdditionalProperties().get("spec");
    }

    @Test
    void singleBuildsOneReplica() {
        var out = adapter.buildCreate(req("SINGLE", 3));

        assertThat(out).hasSize(1);
        var res = out.get(0).resource();
        assertThat(res.getApiVersion()).isEqualTo("rabbitmq.com/v1beta1");
        assertThat(res.getKind()).isEqualTo("RabbitmqCluster");
        assertThat(spec(res)).containsEntry("replicas", 1);
    }

    @Test
    void clusterEnforcesThreeReplicas() {
        assertThat(spec(adapter.buildCreate(req("CLUSTER", 2)).get(0).resource()))
                .containsEntry("replicas", 3);
    }

    @Test
    void persistenceAndResourcesAreRendered() {
        var res = adapter.buildCreate(req("CLUSTER", 3)).get(0).resource();
        @SuppressWarnings("unchecked")
        var persistence = (Map<String, Object>) spec(res).get("persistence");
        assertThat(persistence).containsEntry("storage", "10Gi");
        assertThat((Map<String, Object>) spec(res).get("resources")).isNotNull();
    }

    @Test
    void operatorGeneratesCredentials() {
        assertThat(adapter.usesPlatformPassword()).isFalse();
        assertThat(adapter.buildCreate(req("SINGLE", 1)).get(0).resource()
                .getAdditionalProperties().toString()).doesNotContain("-credentials");
    }

    @Test
    void scalingSingleToMoreReplicasIsRejected() {
        var live = adapter.buildCreate(req("SINGLE", 1)).get(0).resource();
        var scale = new com.coding.common.models.middleware.MiddlewareScaleRequestDTO();
        scale.setMode("SINGLE");
        scale.setReplicas(3);

        assertThatThrownBy(() -> adapter.applyScale(live, RabbitmqMiddlewareAdapter.CRD, scale))
                .hasMessageContaining("单节点形态不支持调整节点数");
    }

    @Test
    void scalingClusterUpdatesReplicas() {
        var live = adapter.buildCreate(req("CLUSTER", 3)).get(0).resource();
        var scale = new com.coding.common.models.middleware.MiddlewareScaleRequestDTO();
        scale.setMode("CLUSTER");
        scale.setReplicas(5);

        adapter.applyScale(live, RabbitmqMiddlewareAdapter.CRD, scale);

        assertThat(spec(live)).containsEntry("replicas", 5);
    }

    @Test
    void selectorsUseClusterOperatorLabels() {
        // 真集群验证：Pod 标签是 name=<集群名>（不是 <名称>-rabbitmq）
        assertThat(adapter.statusSelector("mw-rmq-x", "RabbitmqCluster"))
                .isEqualTo("app.kubernetes.io/name=mw-rmq-x,app.kubernetes.io/component=rabbitmq");
        assertThat(adapter.pvcSelector("mw-rmq-x", "mwi_1"))
                .isEqualTo("app.kubernetes.io/name=mw-rmq-x");
        assertThat(adapter.desiredPods(adapter.buildCreate(req("CLUSTER", 3)).get(0).resource(),
                "RabbitmqCluster")).isEqualTo(3);
    }
}
