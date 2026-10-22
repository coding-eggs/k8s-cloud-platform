package com.coding.k8sserver.middleware;

import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaMiddlewareAdapterTest {

    private final KafkaMiddlewareAdapter adapter = new KafkaMiddlewareAdapter();

    private MiddlewareCreateRequestDTO req(int replicas) {
        MiddlewareCreateRequestDTO r = new MiddlewareCreateRequestDTO();
        r.setInstanceId("mwi_1");
        r.setClusterId("c1");
        r.setCode("kafka");
        r.setMode("CLUSTER");
        r.setNamespace("demo");
        r.setName("mw-kafka-abc12345");
        r.setReplicas(replicas);
        r.setCpuRequest("500m");
        r.setCpuLimit("2");
        r.setMemoryRequest("1Gi");
        r.setMemoryLimit("4Gi");
        r.setStorageSize("20Gi");
        return r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> spec(GenericKubernetesResource res) {
        return (Map<String, Object>) res.getAdditionalProperties().get("spec");
    }

    private GenericKubernetesResource byKind(List<MiddlewareAdapter.CrSpec> out, String kind) {
        return out.stream().filter(c -> kind.equals(c.resource().getKind())).findFirst().orElseThrow().resource();
    }

    @Test
    void buildsNodePoolAndKafka() {
        var out = adapter.buildCreate(req(3));

        assertThat(out).extracting(c -> c.resource().getKind()).containsExactly("KafkaNodePool", "Kafka");
        assertThat(spec(byKind(out, "KafkaNodePool")))
                .containsEntry("replicas", 3)
                .containsEntry("roles", List.of("controller", "broker"));
        assertThat(byKind(out, "Kafka").getMetadata().getAnnotations())
                .containsEntry("strimzi.io/node-pools", "enabled");
    }

    @Test
    void clusterNeedsAtLeastThreeBrokers() {
        var out = adapter.buildCreate(req(1));

        assertThat(spec(byKind(out, "KafkaNodePool"))).containsEntry("replicas", 3);
    }

    @Test
    void kraftStorageUsesSharedMetadata() {
        var out = adapter.buildCreate(req(3));
        @SuppressWarnings("unchecked")
        var storage = (Map<String, Object>) spec(byKind(out, "KafkaNodePool")).get("storage");
        @SuppressWarnings("unchecked")
        var volumes = (List<Map<String, Object>>) storage.get("volumes");
        assertThat(volumes.get(0))
                .containsEntry("type", "persistent-claim")
                .containsEntry("size", "20Gi")
                .containsEntry("kraftMetadata", "shared");
    }

    @Test
    void replicationFactorsFollowBrokerCount() {
        var out = adapter.buildCreate(req(5));
        @SuppressWarnings("unchecked")
        var kafka = (Map<String, Object>) spec(byKind(out, "Kafka")).get("kafka");
        @SuppressWarnings("unchecked")
        var config = (Map<String, Object>) kafka.get("config");
        assertThat(config).containsEntry("default.replication.factor", 3);
        assertThat(config).containsEntry("min.insync.replicas", 2);
    }

    @Test
    void kafkaHasNoPlatformPassword() {
        assertThat(adapter.usesPlatformPassword()).isFalse();
    }

    @Test
    void statusAndPvcSelectorUseStrimziLabel() {
        assertThat(adapter.statusSelector("mw-kafka-x")).isEqualTo("strimzi.io/cluster=mw-kafka-x");
        assertThat(adapter.pvcSelector("mw-kafka-x", "mwi_1")).isEqualTo("strimzi.io/cluster=mw-kafka-x");
    }
}
