package com.coding.k8score.converter.impl.gateway;

import com.coding.common.models.k8s.dto.BackendRefDTO;
import com.coding.common.models.k8s.dto.GrpcRouteDTO;
import com.coding.common.models.k8s.dto.ParentRefDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GrpcRouteConverter 双向转换与 fetch-overlay 保真。
 * <p>与 HTTPRoute 的关键差异是本批的建模判据：<b>没有 path / queryParams</b>（gRPC 按 service/method 路由），
 * filter 只有 4 种（没有 RequestRedirect / URLRewrite）。后三个测试钉住 atomic list 的 overlay。
 */
class GrpcRouteConverterTest {

    private final GrpcRouteConverter c = new GrpcRouteConverter();

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
        res.setMetadata(new ObjectMetaBuilder().withName("route").withNamespace("team-a").build());
        res.setAdditionalProperty("spec", spec);
        return res;
    }

    private static BackendRefDTO backend(String name, int port) {
        BackendRefDTO b = new BackendRefDTO();
        b.setName(name);
        b.setPort(port);
        return b;
    }

    private static GrpcRouteDTO.Rule rule(BackendRefDTO... backends) {
        GrpcRouteDTO.Rule r = new GrpcRouteDTO.Rule();
        GrpcRouteDTO.Match m = new GrpcRouteDTO.Match();
        GrpcRouteDTO.MethodMatch method = new GrpcRouteDTO.MethodMatch();
        method.setService("helloworld.Greeter");
        method.setMethod("SayHello");
        m.setMethod(method);
        r.setMatches(List.of(m));
        r.setBackendRefs(List.of(backends));
        return r;
    }

    private static GrpcRouteDTO sample() {
        GrpcRouteDTO dto = new GrpcRouteDTO();
        dto.setName("greeter");
        dto.setNamespace("team-a");
        ParentRefDTO parent = new ParentRefDTO();
        parent.setName("mesh-gw");
        parent.setSectionName("grpc");
        dto.setParentRefs(List.of(parent));
        dto.setHostnames(List.of("grpc.example.com"));
        dto.setRules(List.of(rule(backend("greeter-svc", 50051))));
        return dto;
    }

    @Test
    @SuppressWarnings("unchecked")
    void convert_emits_method_match_without_path_or_queryParams() {
        GenericKubernetesResource res = c.convert(sample());

        assertThat(res.getApiVersion()).isEqualTo("gateway.networking.k8s.io/v1");
        assertThat(res.getKind()).isEqualTo("GRPCRoute");
        Map<String, Object> spec = specOf(res);
        assertThat(spec).containsEntry("hostnames", List.of("grpc.example.com"));

        Map<String, Object> match = ((List<Map<String, Object>>) rulesOf(spec).get(0).get("matches")).get(0);
        Map<String, Object> method = (Map<String, Object>) match.get("method");
        assertThat(method).containsEntry("service", "helloworld.Greeter").containsEntry("method", "SayHello")
                .doesNotContainKey("type"); // '' 不写，交还 CRD 默认 Exact
        assertThat(match).doesNotContainKeys("path", "queryParams");
    }

    @Test
    @SuppressWarnings("unchecked")
    void convert_emits_backend_and_parent_refs() {
        Map<String, Object> spec = specOf(c.convert(sample()));

        List<Map<String, Object>> parents = (List<Map<String, Object>>) spec.get("parentRefs");
        assertThat(parents).hasSize(1);
        assertThat(parents.get(0)).containsEntry("name", "mesh-gw").containsEntry("sectionName", "grpc");

        List<Map<String, Object>> backends = (List<Map<String, Object>>) rulesOf(spec).get(0).get("backendRefs");
        assertThat(backends).hasSize(1);
        assertThat(backends.get(0)).containsEntry("name", "greeter-svc").containsEntry("port", 50051);
    }

    @Test
    void revert_reads_spec_and_status_parents() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("parentRefs", List.of(Map.of("name", "mesh-gw")));
        spec.put("hostnames", List.of("grpc.example.com"));
        spec.put("rules", List.of(Map.of(
                "name", "r1",
                "matches", List.of(Map.of(
                        "method", Map.of("type", "RegularExpression", "service", "helloworld\\..*"),
                        "headers", List.of(Map.of("name", "x-tenant", "value", "a")))),
                "backendRefs", List.of(Map.of("name", "svc", "port", 50051, "weight", 7)))));
        GenericKubernetesResource res = live(spec);
        res.setAdditionalProperty("status", Map.of("parents", List.of(Map.of(
                "parentRef", Map.of("name", "mesh-gw"),
                "controllerName", "istio.io/gateway-controller",
                "conditions", List.of(Map.of("type", "Accepted", "status", "True"))))));

        GrpcRouteDTO dto = c.revert(res);

        assertThat(dto.getHostnames()).containsExactly("grpc.example.com");
        GrpcRouteDTO.Rule r = dto.getRules().get(0);
        assertThat(r.getName()).isEqualTo("r1");
        assertThat(r.getMatches().get(0).getMethod().getType()).isEqualTo("RegularExpression");
        assertThat(r.getMatches().get(0).getMethod().getService()).isEqualTo("helloworld\\..*");
        assertThat(r.getMatches().get(0).getHeaders().get(0).getName()).isEqualTo("x-tenant");
        assertThat(r.getBackendRefs().get(0).getWeight()).isEqualTo(7);
        assertThat(dto.getParentStatuses().get(0).getControllerName()).isEqualTo("istio.io/gateway-controller");
        assertThat(dto.getParentStatuses().get(0).getConditions().get(0).getType()).isEqualTo("Accepted");
    }

    @Test
    void revert_null_resource_returns_empty_dto() {
        GrpcRouteDTO dto = c.revert(null);
        assertThat(dto.getName()).isNull();
        assertThat(dto.getRules()).isNull();
    }

    // ==================== fetch-overlay（atomic list） ====================

    @Test
    @SuppressWarnings("unchecked")
    void convertForUpdate_keeps_unmodeled_rule_field_and_overwrites_modeled_ones() {
        Map<String, Object> liveRule = new LinkedHashMap<>();
        liveRule.put("matches", List.of(Map.of("method", Map.of("service", "old.Svc"))));
        liveRule.put("backendRefs", List.of(Map.of("name", "old-svc", "port", 50051)));
        liveRule.put("sessionPersistence", Map.of("sessionName", "sess")); // 未建模
        Map<String, Object> liveSpec = new LinkedHashMap<>();
        liveSpec.put("rules", List.of(liveRule));
        liveSpec.put("useDefaultGateways", "All"); // v1.6 未建模 spec 键

        GrpcRouteDTO dto = sample();
        dto.setRules(List.of(rule(backend("new-svc", 50052))));

        Map<String, Object> spec = specOf(c.convertForUpdate(dto, live(liveSpec)));

        assertThat(spec).containsEntry("useDefaultGateways", "All");
        Map<String, Object> outRule = rulesOf(spec).get(0);
        assertThat(outRule).containsEntry("sessionPersistence", Map.of("sessionName", "sess"));
        assertThat((List<Map<String, Object>>) outRule.get("backendRefs")).hasSize(1);
        assertThat(((List<Map<String, Object>>) outRule.get("backendRefs")).get(0)).containsEntry("name", "new-svc");
    }

    @Test
    @SuppressWarnings("unchecked")
    void convertForUpdate_preserves_unmodeled_filter_element_and_drops_removed_modeled_one() {
        // 线上：一个未建模 type（假想的实现私有 filter）+ 一个建模 type（表单里被移除）
        Map<String, Object> liveVendor = Map.of("type", "VendorThrottle", "vendorThrottle", Map.of("rps", 10));
        Map<String, Object> liveMirror = Map.of("type", "RequestMirror",
                "requestMirror", Map.of("backendRef", Map.of("name", "shadow", "port", 50051)));
        Map<String, Object> liveRule = new LinkedHashMap<>();
        liveRule.put("filters", List.of(liveVendor, liveMirror));
        liveRule.put("backendRefs", List.of(Map.of("name", "svc", "port", 50051)));
        Map<String, Object> liveSpec = new LinkedHashMap<>();
        liveSpec.put("rules", List.of(liveRule));

        GrpcRouteDTO.Filter added = new GrpcRouteDTO.Filter();
        added.setType("RequestHeaderModifier");
        GrpcRouteDTO.Rule dtoRule = rule(backend("svc", 50051));
        dtoRule.setFilters(List.of(added));
        GrpcRouteDTO dto = sample();
        dto.setRules(List.of(dtoRule));

        List<Map<String, Object>> filters = (List<Map<String, Object>>) rulesOf(specOf(c.convertForUpdate(dto, live(liveSpec))))
                .get(0).get("filters");

        assertThat(filters).extracting(f -> f.get("type")).containsExactly("VendorThrottle", "RequestHeaderModifier");
        assertThat(filters.get(0)).containsEntry("vendorThrottle", Map.of("rps", 10));
    }

    @Test
    @SuppressWarnings("unchecked")
    void convertForUpdate_overlays_matches_by_index_keeping_unmodeled_sibling_keys() {
        Map<String, Object> liveMatch = new LinkedHashMap<>();
        liveMatch.put("method", Map.of("service", "old.Svc"));
        liveMatch.put("someFutureMatchField", "keep-me");
        Map<String, Object> liveRule = new LinkedHashMap<>();
        liveRule.put("matches", List.of(liveMatch));
        liveRule.put("backendRefs", List.of(Map.of("name", "svc", "port", 50051)));
        Map<String, Object> liveSpec = new LinkedHashMap<>();
        liveSpec.put("rules", List.of(liveRule));

        GrpcRouteDTO dto = sample();
        dto.setRules(List.of(rule(backend("svc", 50051))));

        Map<String, Object> match = ((List<Map<String, Object>>) rulesOf(specOf(c.convertForUpdate(dto, live(liveSpec))))
                .get(0).get("matches")).get(0);

        assertThat(match).containsEntry("someFutureMatchField", "keep-me");
        assertThat((Map<String, Object>) match.get("method")).containsEntry("service", "helloworld.Greeter");
    }

    @Test
    @SuppressWarnings("unchecked")
    void convertForUpdate_can_clear_parentRefs_and_hostnames() {
        Map<String, Object> liveSpec = new LinkedHashMap<>();
        liveSpec.put("parentRefs", List.of(Map.of("name", "gw")));
        liveSpec.put("hostnames", List.of("a.example.com"));
        liveSpec.put("rules", List.of(Map.of("backendRefs", List.of(Map.of("name", "svc", "port", 50051)))));

        GrpcRouteDTO dto = new GrpcRouteDTO();
        dto.setName("greeter");
        dto.setNamespace("team-a");
        dto.setRules(List.of(rule(backend("svc", 50051))));

        Map<String, Object> spec = specOf(c.convertForUpdate(dto, live(liveSpec)));

        assertThat(spec).doesNotContainKeys("parentRefs", "hostnames");
    }

}
