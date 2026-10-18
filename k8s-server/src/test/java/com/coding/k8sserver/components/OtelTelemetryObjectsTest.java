package com.coding.k8sserver.components;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OtelTelemetryObjectsTest {

    @Test
    void itTargetsTheGatewayAndTheOtelProvider() {
        GenericKubernetesResource telemetry = OtelTelemetryObjects.build(
                "team-a", "platform-tracing-wp", "waypoint", 10);

        assertThat(telemetry.getApiVersion()).isEqualTo("telemetry.istio.io/v1");
        assertThat(telemetry.getKind()).isEqualTo("Telemetry");
        assertThat(telemetry.getMetadata().getName()).isEqualTo("platform-tracing-wp");
        assertThat(telemetry.getMetadata().getNamespace()).isEqualTo("team-a");

        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) telemetry.getAdditionalProperties().get("spec");
        assertThat(spec.get("targetRefs").toString())
                .contains("gateway.networking.k8s.io")
                .contains("Gateway")
                .contains("waypoint");
        assertThat(spec.get("tracing").toString())
                .contains("platform-otel")
                .contains("10");
    }
}
