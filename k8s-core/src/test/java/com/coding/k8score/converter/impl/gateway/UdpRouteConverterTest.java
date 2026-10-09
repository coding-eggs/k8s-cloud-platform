package com.coding.k8score.converter.impl.gateway;

import com.coding.common.models.k8s.dto.BackendRefDTO;
import com.coding.common.models.k8s.dto.L4RouteRuleDTO;
import com.coding.common.models.k8s.dto.ParentRefDTO;
import com.coding.common.models.k8s.dto.UdpRouteDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UdpRouteConverter。与 TCPRoute 同构（CRD 里 UDPRouteRule 就是 v1 类型的别名），
 * 只做 kind/plural/apiVersion 与"不发 hostnames"的确认。
 */
class UdpRouteConverterTest {

    private final UdpRouteConverter c = new UdpRouteConverter("v1");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> specOf(GenericKubernetesResource res) {
        return (Map<String, Object>) res.getAdditionalProperties().get("spec");
    }

    private GenericKubernetesResource live(Map<String, Object> spec) {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setMetadata(new ObjectMetaBuilder().withName("udp").withNamespace("team-a").build());
        res.setAdditionalProperty("spec", spec);
        return res;
    }

    private static UdpRouteDTO sample() {
        UdpRouteDTO dto = new UdpRouteDTO();
        dto.setName("dns-route");
        dto.setNamespace("team-a");
        ParentRefDTO parent = new ParentRefDTO();
        parent.setName("udp-gw");
        dto.setParentRefs(List.of(parent));

        BackendRefDTO backend = new BackendRefDTO();
        backend.setName("coredns");
        backend.setPort(53);
        L4RouteRuleDTO rule = new L4RouteRuleDTO();
        rule.setBackendRefs(List.of(backend));
        dto.setRules(List.of(rule));
        return dto;
    }

    @Test
    @SuppressWarnings("unchecked")
    void convert_emits_kind_rules_and_no_hostnames() {
        GenericKubernetesResource res = c.convert(sample());

        assertThat(res.getKind()).isEqualTo("UDPRoute");
        assertThat(res.getApiVersion()).isEqualTo("gateway.networking.k8s.io/v1");
        Map<String, Object> spec = specOf(res);
        assertThat(spec).doesNotContainKey("hostnames"); // UDPRoute 没有 hostnames 字段
        List<Map<String, Object>> rules = (List<Map<String, Object>>) spec.get("rules");
        assertThat(rules).hasSize(1);
        assertThat(((List<Map<String, Object>>) rules.get(0).get("backendRefs")).get(0)).containsEntry("port", 53);
    }

    @Test
    void revert_reads_rules() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("rules", List.of(Map.of("backendRefs", List.of(Map.of("name", "coredns", "port", 53)))));

        UdpRouteDTO dto = c.revert(live(spec));

        assertThat(dto.getRules().get(0).getBackendRefs().get(0).getName()).isEqualTo("coredns");
    }

    @Test
    @SuppressWarnings("unchecked")
    void convertForUpdate_keeps_unmodeled_spec_keys() {
        Map<String, Object> liveSpec = new LinkedHashMap<>();
        liveSpec.put("rules", List.of(Map.of("backendRefs", List.of(Map.of("name", "old", "port", 53)))));
        liveSpec.put("useDefaultGateways", "All");

        Map<String, Object> spec = specOf(c.convertForUpdate(sample(), live(liveSpec)));

        assertThat(spec).containsEntry("useDefaultGateways", "All");
        assertThat(((List<Map<String, Object>>) spec.get("rules")).get(0)).containsKey("backendRefs");
    }

    @Test
    void revert_null_resource_returns_empty_dto() {
        assertThat(c.revert(null).getName()).isNull();
    }

}
