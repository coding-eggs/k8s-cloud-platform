package com.coding.k8sserver.middleware;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka：加第二个 SCRAM listener（不动 plain），用户 = KafkaUser CR。
 */
@SuppressWarnings("unchecked")
class KafkaUserAdapterTest {

    private final KafkaUserAdapter adapter = new KafkaUserAdapter();

    @Test
    void keepsPlainListenerAndAddsScramWithoutTouchingAuthorization() {
        List<Map<String, Object>> listeners = KafkaMiddlewareAdapter.listeners();

        assertThat(listeners).extracting(l -> l.get("name")).containsExactly("plain", "scram");
        assertThat(listeners.get(0)).containsEntry("port", 9092);
        assertThat(listeners.get(1)).containsEntry("port", 9093);
        assertThat((Map<String, Object>) listeners.get(1).get("authentication"))
                .containsEntry("type", "scram-sha-512");
    }

    @Test
    void userCrUsesScramAndBindsToTheClusterByLabel() {
        GenericKubernetesResource cr = KafkaUserAdapter.userCr("alice", "mw-kafka-abcdefgh");

        assertThat(cr.getKind()).isEqualTo("KafkaUser");
        assertThat(cr.getMetadata().getName()).isEqualTo("alice");
        // Strimzi 靠这个标签把用户挂到 Kafka 上；漏了永远不会生成凭据
        assertThat(cr.getMetadata().getLabels())
                .containsEntry("strimzi.io/cluster", "mw-kafka-abcdefgh");
        assertThat((Map<String, Object>) cr.getAdditionalProperties().get("spec"))
                .containsEntry("authentication", Map.of("type", "scram-sha-512"));
    }

    @Test
    void onlyTheClientRoleIsOfferedBecauseAuthorizationIsOff() {
        assertThat(adapter.roles(null, new MiddlewareUserRef("mw-kafka-abcdefgh", "demo", "mwi_1")))
                .containsExactly("client");
    }

    @Test
    void hasScramListenerReadsTheKafkaCr() {
        var withScram = cr(java.util.Map.of("name", "scram", "port", 9093,
                "authentication", java.util.Map.of("type", "scram-sha-512")));
        var plainOnly = cr(java.util.Map.of("name", "plain", "port", 9092, "tls", false));

        assertThat(KafkaUserAdapter.hasScramListener(withScram)).isTrue();
        assertThat(KafkaUserAdapter.hasScramListener(plainOnly)).isFalse();
        assertThat(KafkaUserAdapter.hasScramListener(new io.fabric8.kubernetes.api.model.GenericKubernetesResource()))
                .isFalse();
    }

    private static io.fabric8.kubernetes.api.model.GenericKubernetesResource cr(java.util.Map<String, Object> listener) {
        var cr = new io.fabric8.kubernetes.api.model.GenericKubernetesResource();
        cr.setAdditionalProperties(java.util.Map.of("spec",
                java.util.Map.of("kafka", java.util.Map.of("listeners", List.of(listener)))));
        return cr;
    }
}
