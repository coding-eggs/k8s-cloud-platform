package com.coding.k8score.converter.impl.gateway;

import com.coding.common.models.k8s.dto.BackendRefDTO;
import com.coding.common.models.k8s.dto.L4RouteRuleDTO;
import com.coding.common.models.k8s.dto.ParentRefDTO;
import com.coding.common.models.k8s.dto.TlsRouteDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TlsRouteConverter。重点是 <b>SNI 走 {@code spec.hostnames}</b>，不是旧实验 API 的
 * {@code rules[].matches[].sniHostname}（那个在 Gateway API 所有 v1.x 版本里都已不存在）。
 */
class TlsRouteConverterTest {

    private final TlsRouteConverter c = new TlsRouteConverter("v1");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> specOf(GenericKubernetesResource res) {
        return (Map<String, Object>) res.getAdditionalProperties().get("spec");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rulesOf(Map<String, Object> spec) {
        return (List<Map<String, Object>>) spec.get("rules");
    }

    private GenericKubernetesResource live(Map<String, Object> spec) {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setMetadata(new ObjectMetaBuilder().withName("tls").withNamespace("team-a").build());
        res.setAdditionalProperty("spec", spec);
        return res;
    }

    private static TlsRouteDTO sample() {
        TlsRouteDTO dto = new TlsRouteDTO();
        dto.setName("tls-route");
        dto.setNamespace("team-a");
        ParentRefDTO parent = new ParentRefDTO();
        parent.setName("passthrough-gw");
        parent.setSectionName("tls-443");
        dto.setParentRefs(List.of(parent));
        dto.setHostnames(List.of("db.example.com", "*.internal.example.com"));

        BackendRefDTO backend = new BackendRefDTO();
        backend.setName("db-svc");
        backend.setPort(5432);
        L4RouteRuleDTO rule = new L4RouteRuleDTO();
        rule.setBackendRefs(List.of(backend));
        dto.setRules(List.of(rule));
        return dto;
    }

    @Test
    @SuppressWarnings("unchecked")
    void convert_puts_sni_in_spec_hostnames_not_in_rules() {
        GenericKubernetesResource res = c.convert(sample());

        assertThat(res.getKind()).isEqualTo("TLSRoute");
        Map<String, Object> spec = specOf(res);
        assertThat(spec).containsEntry("hostnames", List.of("db.example.com", "*.internal.example.com"));

        Map<String, Object> rule = rulesOf(spec).get(0);
        // rule 里不该出现任何匹配结构
        assertThat(rule).doesNotContainKeys("matches", "sniHostname");
        assertThat(rule).containsKey("backendRefs");
    }

    @Test
    void revert_reads_hostnames_from_spec() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("parentRefs", List.of(Map.of("name", "passthrough-gw")));
        spec.put("hostnames", List.of("db.example.com"));
        spec.put("rules", List.of(Map.of("backendRefs", List.of(Map.of("name", "db-svc", "port", 5432)))));

        TlsRouteDTO dto = c.revert(live(spec));

        assertThat(dto.getHostnames()).containsExactly("db.example.com");
        assertThat(dto.getRules().get(0).getBackendRefs().get(0).getName()).isEqualTo("db-svc");
    }

    @Test
    @SuppressWarnings("unchecked")
    void convertForUpdate_clears_hostnames_when_form_is_empty_but_keeps_unmodeled_spec_keys() {
        Map<String, Object> liveSpec = new LinkedHashMap<>();
        liveSpec.put("hostnames", List.of("old.example.com"));
        liveSpec.put("rules", List.of(Map.of("backendRefs", List.of(Map.of("name", "db-svc", "port", 5432)))));
        liveSpec.put("useDefaultGateways", "All");

        TlsRouteDTO dto = sample();
        dto.setHostnames(null);

        Map<String, Object> spec = specOf(c.convertForUpdate(dto, live(liveSpec)));

        assertThat(spec).doesNotContainKey("hostnames");
        assertThat(spec).containsEntry("useDefaultGateways", "All");
        assertThat(rulesOf(spec)).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void convertForUpdate_overwrites_hostnames() {
        Map<String, Object> liveSpec = new LinkedHashMap<>();
        liveSpec.put("hostnames", List.of("old.example.com"));
        liveSpec.put("rules", List.of(Map.of("backendRefs", List.of(Map.of("name", "svc", "port", 5432)))));

        Map<String, Object> spec = specOf(c.convertForUpdate(sample(), live(liveSpec)));

        assertThat(spec).containsEntry("hostnames", List.of("db.example.com", "*.internal.example.com"));
    }

    @Test
    void revert_null_resource_returns_empty_dto() {
        TlsRouteDTO dto = c.revert(null);
        assertThat(dto.getName()).isNull();
        assertThat(dto.getHostnames()).isNull();
    }

}
