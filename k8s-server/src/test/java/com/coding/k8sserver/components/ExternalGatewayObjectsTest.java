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

    @Test
    void hostnameIsEmittedOnTheListener() {
        GatewayListenerDTO https = listener("harbor-https", 443, "HTTPS", "harbor-tls");
        https.setHostname("harbor.ackcp.cloud");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> specs = ExternalGatewayObjects.listenerSpecs(List.of(https));

        assertThat(specs.get(0)).containsEntry("hostname", "harbor.ackcp.cloud")
                .containsEntry("port", 443);
        // 没写 hostname 的 listener 不能凭空冒出一个 hostname 字段
        assertThat(ExternalGatewayObjects.listenerSpecs(List.of(listener("http2", 80, "HTTP", null)))
                .get(0)).doesNotContainKey("hostname");
    }

    @Test
    void mergeByNameKeepsTheIstioPortTable() {
        List<Map<String, Object>> existing = ExternalGatewayObjects.listenerSpecs(List.of(
                listener("http2", 80, "HTTP", null),
                listener("seaweedfs-s3", 38333, "HTTP", null)));
        GatewayListenerDTO harbor = listener("harbor-https", 443, "HTTPS", "harbor-tls");
        harbor.setHostname("harbor.ackcp.cloud");

        List<Map<String, Object>> merged = ExternalGatewayObjects.mergeByName(
                existing, ExternalGatewayObjects.listenerSpecs(List.of(harbor)));

        assertThat(merged).hasSize(3);
        assertThat(merged.stream().map(m -> m.get("name"))).containsExactly(
                "http2", "seaweedfs-s3", "harbor-https");
    }

    @Test
    void mergeByNameReplacesSameName() {
        List<Map<String, Object>> existing = ExternalGatewayObjects.listenerSpecs(
                List.of(listener("harbor-https", 443, "HTTPS", "old-cert")));
        GatewayListenerDTO updated = listener("harbor-https", 443, "HTTPS", "new-cert");
        updated.setHostname("harbor.ackcp.cloud");

        List<Map<String, Object>> merged = ExternalGatewayObjects.mergeByName(
                existing, ExternalGatewayObjects.listenerSpecs(List.of(updated)));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).get("tls").toString()).contains("new-cert")
                .doesNotContain("old-cert");
    }
}
