package com.coding.k8score.converter.impl.gateway;

import com.coding.common.models.k8s.dto.ListenerSetDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ListenerSetConverterTest {

    private final ListenerSetConverter c = new ListenerSetConverter();

    @Test
    void revertReadsParentListenersAndStatus() {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setMetadata(new ObjectMetaBuilder().withName("platform-ingress").withNamespace("shop")
                .withCreationTimestamp("2026-11-08T10:00:00Z").build());
        res.setAdditionalProperty("spec", Map.of(
                "parentRef", Map.of("name", "external-gateway", "namespace", "istio-system"),
                "listeners", List.of(
                        Map.of("name", "p20001", "port", 20001, "protocol", "HTTP"),
                        Map.of("name", "p20002", "port", 20002, "protocol", "TCP"))));
        res.setAdditionalProperty("status", Map.of(
                "conditions", List.of(Map.of("type", "Accepted", "status", "True", "reason", "Accepted")),
                "listeners", List.of(Map.of("name", "p20001", "attachedRoutes", 1,
                        "conditions", List.of(Map.of("type", "Programmed", "status", "True"))))));

        ListenerSetDTO dto = c.revert(res);

        assertThat(dto.getName()).isEqualTo("platform-ingress");
        assertThat(dto.getNamespace()).isEqualTo("shop");
        assertThat(dto.getCreationTime()).isEqualTo("2026-11-08T10:00:00Z");
        assertThat(dto.getParentName()).isEqualTo("external-gateway");
        assertThat(dto.getParentNamespace()).isEqualTo("istio-system");
        assertThat(dto.getListeners()).extracting(ListenerSetDTO.Listener::getName).containsExactly("p20001", "p20002");
        assertThat(dto.getListeners()).extracting(ListenerSetDTO.Listener::getPort).containsExactly(20001, 20002);
        assertThat(dto.getListeners()).extracting(ListenerSetDTO.Listener::getProtocol).containsExactly("HTTP", "TCP");
        assertThat(dto.getConditions()).hasSize(1);
        assertThat(dto.getConditions().get(0).getType()).isEqualTo("Accepted");
        assertThat(dto.getListenerStatuses()).hasSize(1);
        assertThat(dto.getListenerStatuses().get(0).getAttachedRoutes()).isEqualTo(1);
        assertThat(dto.getListenerStatuses().get(0).getConditions().get(0).getType()).isEqualTo("Programmed");
    }

    @Test
    void parentNamespaceDefaultsToOwnNamespace() {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setMetadata(new ObjectMetaBuilder().withName("ls").withNamespace("shop").build());
        res.setAdditionalProperty("spec", Map.of("parentRef", Map.of("name", "gw")));

        ListenerSetDTO dto = c.revert(res);

        assertThat(dto.getParentNamespace()).isEqualTo("shop");
        assertThat(dto.getListeners()).isNull();
        assertThat(dto.getListenerStatuses()).isNull();
    }

    @Test
    void revertOfNullIsEmpty() {
        assertThat(c.revert(null).getName()).isNull();
    }
}
