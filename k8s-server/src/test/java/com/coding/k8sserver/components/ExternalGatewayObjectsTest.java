package com.coding.k8sserver.components;

import com.coding.common.models.k8s.dto.GatewayListenerDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalGatewayObjectsTest {

    private static GatewayListenerDTO listener(String name, int port, String protocol, String secret) {
        GatewayListenerDTO dto = new GatewayListenerDTO();
        dto.setName(name);
        dto.setPort(port);
        dto.setProtocol(protocol);
        dto.setTlsSecret(secret);
        return dto;
    }

    @Test
    void itBindsTheGatewayToItsServiceAndMapsListeners() {
        GenericKubernetesResource gateway = ExternalGatewayObjects.build(
                "istio-system", "external-gateway", "external-gateway",
                List.of(listener("http2", 80, "HTTP", null),
                        listener("https", 443, "TLS", null),
                        listener("admin", 8443, "HTTPS", "gw-cert")));

        assertThat(gateway.getApiVersion()).isEqualTo("gateway.networking.k8s.io/v1");
        assertThat(gateway.getKind()).isEqualTo("Gateway");
        assertThat(gateway.getMetadata().getName()).isEqualTo("external-gateway");
        assertThat(gateway.getMetadata().getNamespace()).isEqualTo("istio-system");

        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) gateway.getAdditionalProperties().get("spec");
        assertThat(spec).containsEntry("gatewayClassName", "istio");
        // 关键：把 CR 绑到承载它的 Service —— 不绑就是 Programmed=False / AddressNotUsable
        assertThat(spec.get("addresses").toString())
                .contains("Hostname")
                .contains("external-gateway.istio-system.svc.cluster.local");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> listeners = (List<Map<String, Object>>) spec.get("listeners");
        assertThat(listeners).hasSize(3);
        assertThat(listeners.get(0)).containsEntry("protocol", "HTTP").containsEntry("port", 80);
        assertThat(listeners.get(0)).doesNotContainKey("tls");
        // TLS = Passthrough，不挂证书
        assertThat(listeners.get(1).get("tls").toString()).contains("Passthrough");
        // HTTPS = Terminate + 证书 Secret
        assertThat(listeners.get(2).get("tls").toString())
                .contains("Terminate").contains("gw-cert");
        assertThat(listeners).allSatisfy(l -> assertThat(l.get("allowedRoutes").toString())
                .contains("All"));
    }
}
