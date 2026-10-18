package com.coding.k8sserver.components;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OtelServiceMonitorObjectsTest {

    @Test
    void itSelectsTheOperatorGeneratedServiceAndThePrometheusPort() {
        GenericKubernetesResource sm = OtelServiceMonitorObjects.build("monitor");

        assertThat(sm.getApiVersion()).isEqualTo("monitoring.coreos.com/v1");
        assertThat(sm.getKind()).isEqualTo("ServiceMonitor");
        assertThat(sm.getMetadata().getNamespace()).isEqualTo("monitor");

        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) sm.getAdditionalProperties().get("spec");
        assertThat(spec.get("selector").toString())
                .contains("app.kubernetes.io/name")
                .contains("platform-otel-gateway-collector");
        assertThat(spec.get("endpoints").toString())
                .contains("prometheus")
                .contains("30s");
    }
}
