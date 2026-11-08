package com.coding.k8sserver.components;

import com.coding.common.models.k8s.dto.IngressListenerDTO;
import com.coding.common.models.k8s.dto.IngressListenerSetRequestDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngressListenerSetObjectsTest {

    private static IngressListenerDTO listener(String name, int port, String protocol) {
        IngressListenerDTO l = new IngressListenerDTO();
        l.setName(name);
        l.setPort(port);
        l.setProtocol(protocol);
        return l;
    }

    private static IngressListenerSetRequestDTO req(String ns, List<IngressListenerDTO> listeners) {
        IngressListenerSetRequestDTO r = new IngressListenerSetRequestDTO();
        r.setClusterId("c1");
        r.setNamespace(ns);
        r.setName("platform-ingress");
        r.setParentNamespace("istio-system");
        r.setParentName("external-gateway");
        r.setNamespaceLabels(Map.of("ingress.platform.coding/ports", "true"));
        r.setListeners(listeners);
        return r;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> spec(GenericKubernetesResource r) {
        return (Map<String, Object>) r.getAdditionalProperties().get("spec");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listeners(GenericKubernetesResource r) {
        return (List<Map<String, Object>>) spec(r).get("listeners");
    }

    @Test
    void buildsListenerSetWithSameNamespaceRoutes() {
        GenericKubernetesResource r = IngressListenerSetObjects.build(req("shop", List.of(
                listener("p20001", 20001, "HTTP"), listener("p20002", 20002, "TCP"))));
        assertThat(r.getApiVersion()).isEqualTo("gateway.networking.k8s.io/v1");
        assertThat(r.getKind()).isEqualTo("ListenerSet");
        assertThat(r.getMetadata().getNamespace()).isEqualTo("shop");
        assertThat(r.getMetadata().getName()).isEqualTo("platform-ingress");
        assertThat(r.getMetadata().getLabels()).containsEntry("platform.coding/managed", "true");
        assertThat(spec(r).get("parentRef")).isEqualTo(Map.of("group", "gateway.networking.k8s.io",
                "kind", "Gateway", "name", "external-gateway", "namespace", "istio-system"));
        assertThat(listeners(r)).extracting(l -> l.get("allowedRoutes"))
                .containsOnly(Map.of("namespaces", Map.of("from", "Same")));
        assertThat(listeners(r)).extracting(l -> l.get("protocol")).containsExactly("HTTP", "TCP");
        assertThat(listeners(r)).extracting(l -> l.get("port")).containsExactly(20001, 20002);
    }

    @Test
    void rejectsBadListeners() {
        assertThatThrownBy(() -> IngressListenerSetObjects.validate(req("shop",
                List.of(listener("p1", 1, "HTTPS"))))).hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> IngressListenerSetObjects.validate(req("shop",
                List.of(listener("a", 20001, "HTTP"), listener("a", 20002, "HTTP"))))).hasMessageContaining("重复");
        assertThatThrownBy(() -> IngressListenerSetObjects.validate(req("shop",
                List.of(listener("a", 20001, "HTTP"), listener("b", 20001, "TCP"))))).hasMessageContaining("重复");
        assertThatThrownBy(() -> IngressListenerSetObjects.validate(req("shop",
                List.of(listener("a", 70000, "HTTP"))))).hasMessageContaining("端口");
        List<IngressListenerDTO> many = new ArrayList<>();
        for (int i = 0; i < 65; i++) {
            many.add(listener("p" + i, 20000 + i, "HTTP"));
        }
        assertThatThrownBy(() -> IngressListenerSetObjects.validate(req("shop", many))).hasMessageContaining("64");
        IngressListenerSetObjects.validate(req("shop", List.of()));   // 空 = 删除，合法
    }
}
