package com.coding.k8sserver.middleware;

import com.coding.common.models.middleware.MiddlewareExposeRequestDTO;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MiddlewareExposeObjectsTest {

    private static MiddlewareExposeRequestDTO req(String mode, String protocol) {
        MiddlewareExposeRequestDTO r = new MiddlewareExposeRequestDTO();
        r.setClusterId("c1");
        r.setNamespace("shop");
        r.setInstanceName("orders");
        r.setEndpointKey("rw");
        r.setMode(mode);
        r.setServiceName("orders-haproxy");
        r.setServicePort(3306);
        r.setProtocol(protocol);
        r.setListenerSetName("platform-ingress");
        r.setSectionName("p20005");
        r.setNodePort(31000);
        return r;
    }

    @Test
    @SuppressWarnings("unchecked")
    void tcpRouteHangsOnTheListenerSetSectionAndPointsAtTheTargetService() {
        var route = MiddlewareExposeObjects.route(req("GATEWAY", "TCP"));

        assertThat(route.getKind()).isEqualTo("TCPRoute");
        assertThat(route.getMetadata().getName()).isEqualTo("orders-expose-rw");
        assertThat(route.getMetadata().getLabels()).containsEntry(MiddlewareExposeObjects.LABEL, "orders");
        Map<String, Object> spec = (Map<String, Object>) route.getAdditionalProperties().get("spec");
        Map<String, Object> parent = ((List<Map<String, Object>>) spec.get("parentRefs")).get(0);
        assertThat(parent).containsEntry("kind", "ListenerSet").containsEntry("name", "platform-ingress")
                .containsEntry("sectionName", "p20005");
        Map<String, Object> rule = ((List<Map<String, Object>>) spec.get("rules")).get(0);
        Map<String, Object> backend = ((List<Map<String, Object>>) rule.get("backendRefs")).get(0);
        assertThat(backend).containsEntry("name", "orders-haproxy").containsEntry("port", 3306);
    }

    @Test
    void httpProtocolBuildsAnHttpRoute() {
        assertThat(MiddlewareExposeObjects.route(req("GATEWAY", "HTTP")).getKind()).isEqualTo("HTTPRoute");
    }

    @Test
    void nodePortServiceCopiesSelectorAndTargetPortOfTheTargetService() {
        Service target = new ServiceBuilder()
                .withNewSpec().withSelector(Map.of("app", "orders"))
                .addNewPort().withName("mysql").withPort(3306).withTargetPort(new IntOrString("mysql")).endPort()
                .addNewPort().withName("ro").withPort(3307).withTargetPort(new IntOrString(3307)).endPort()
                .endSpec().build();

        Service svc = MiddlewareExposeObjects.nodePortService(req("NODEPORT", null), target);

        assertThat(svc.getMetadata().getName()).isEqualTo("orders-expose-rw");
        assertThat(svc.getSpec().getType()).isEqualTo("NodePort");
        assertThat(svc.getSpec().getSelector()).containsEntry("app", "orders");
        assertThat(svc.getSpec().getPorts()).hasSize(1);
        assertThat(svc.getSpec().getPorts().get(0).getTargetPort().getStrVal()).isEqualTo("mysql");
        assertThat(svc.getSpec().getPorts().get(0).getNodePort()).isEqualTo(31000);
    }

    @Test
    void nodePortServiceFailsWhenTheTargetIsMissingOrLacksThePort() {
        assertThatThrownBy(() -> MiddlewareExposeObjects.nodePortService(req("NODEPORT", null), null))
                .isInstanceOf(IllegalStateException.class);
        Service other = new ServiceBuilder().withNewSpec().withSelector(Map.of("a", "b"))
                .addNewPort().withPort(1).endPort().endSpec().build();
        assertThatThrownBy(() -> MiddlewareExposeObjects.nodePortService(req("NODEPORT", null), other))
                .hasMessageContaining("3306");
    }

    @Test
    void validateRejectsUnknownModeAndBadProtocol() {
        assertThatThrownBy(() -> MiddlewareExposeObjects.validate(req("WHAT", "TCP")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MiddlewareExposeObjects.validate(req("GATEWAY", "UDP")))
                .isInstanceOf(IllegalArgumentException.class);
        MiddlewareExposeObjects.validate(req("NONE", null));   // 不抛
    }
}
