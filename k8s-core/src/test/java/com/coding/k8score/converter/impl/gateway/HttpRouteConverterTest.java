package com.coding.k8score.converter.impl.gateway;

import com.coding.common.models.k8s.dto.BackendRefDTO;
import com.coding.common.models.k8s.dto.HttpRouteDTO;
import com.coding.common.models.k8s.dto.ParentRefDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HttpRouteConverter 的双向转换与 <b>fetch-overlay 保真</b>。
 * <p>后三个测试是本批的核心验收点（spec §10.3 / 计划验收 3）：rules 是 atomic list，
 * 平台编辑一次不得丢掉未建模的 rule 级字段与 CORS filter。
 */
class HttpRouteConverterTest {

    private final HttpRouteConverter c = new HttpRouteConverter();

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

    private static HttpRouteDTO.Rule rule(BackendRefDTO... backends) {
        HttpRouteDTO.Rule r = new HttpRouteDTO.Rule();
        HttpRouteDTO.Match m = new HttpRouteDTO.Match();
        HttpRouteDTO.PathMatch p = new HttpRouteDTO.PathMatch();
        p.setType("PathPrefix");
        p.setValue("/api");
        m.setPath(p);
        r.setMatches(List.of(m));
        r.setBackendRefs(List.of(backends));
        return r;
    }

    private static BackendRefDTO backend(String name, int port) {
        BackendRefDTO b = new BackendRefDTO();
        b.setName(name);
        b.setPort(port);
        return b;
    }

    private static HttpRouteDTO sample() {
        HttpRouteDTO dto = new HttpRouteDTO();
        dto.setName("route");
        dto.setNamespace("team-a");
        ParentRefDTO parent = new ParentRefDTO();
        parent.setName("gw");
        parent.setSectionName("http");
        dto.setParentRefs(List.of(parent));
        dto.setHostnames(List.of("api.example.com"));
        dto.setRules(List.of(rule(backend("svc-a", 8080))));
        return dto;
    }

    @Test
    @SuppressWarnings("unchecked")
    void convert_emits_namespaced_spec() {
        GenericKubernetesResource res = c.convert(sample());

        assertThat(res.getApiVersion()).isEqualTo("gateway.networking.k8s.io/v1");
        assertThat(res.getKind()).isEqualTo("HTTPRoute");
        assertThat(res.getMetadata().getNamespace()).isEqualTo("team-a");
        Map<String, Object> spec = specOf(res);
        assertThat(spec).containsEntry("hostnames", List.of("api.example.com"));

        // parentRefs：group/kind 缺省不写（由 CRD 推断 Gateway）
        List<Map<String, Object>> parents = (List<Map<String, Object>>) spec.get("parentRefs");
        assertThat(parents).hasSize(1);
        assertThat(parents.get(0)).containsEntry("name", "gw").containsEntry("sectionName", "http")
                .doesNotContainKeys("group", "kind", "namespace");

        List<Map<String, Object>> rules = rulesOf(spec);
        assertThat(rules).hasSize(1);
        List<Map<String, Object>> matches = (List<Map<String, Object>>) rules.get(0).get("matches");
        assertThat((Map<String, Object>) matches.get(0).get("path"))
                .containsEntry("type", "PathPrefix").containsEntry("value", "/api");
        List<Map<String, Object>> backends = (List<Map<String, Object>>) rules.get(0).get("backendRefs");
        assertThat(backends).hasSize(1);
        assertThat(backends.get(0)).containsEntry("name", "svc-a").containsEntry("port", 8080)
                .doesNotContainKeys("group", "kind", "namespace", "weight");
    }

    @Test
    @SuppressWarnings("unchecked")
    void convert_emits_filter_bodies_keyed_by_type() {
        HttpRouteDTO.Filter f = new HttpRouteDTO.Filter();
        f.setType("RequestHeaderModifier");
        HttpRouteDTO.HeaderFilter hf = new HttpRouteDTO.HeaderFilter();
        HttpRouteDTO.HeaderValue hv = new HttpRouteDTO.HeaderValue();
        hv.setName("X-Team");
        hv.setValue("a");
        hf.setSet(List.of(hv));
        hf.setRemove(List.of("X-Drop"));
        f.setRequestHeaderModifier(hf);

        HttpRouteDTO.Rule r = rule(backend("svc-a", 8080));
        r.setFilters(List.of(f));
        HttpRouteDTO dto = sample();
        dto.setRules(List.of(r));

        Map<String, Object> filter = ((List<Map<String, Object>>) rulesOf(specOf(c.convert(dto))).get(0).get("filters")).get(0);

        assertThat(filter).containsEntry("type", "RequestHeaderModifier");
        Map<String, Object> body = (Map<String, Object>) filter.get("requestHeaderModifier");
        assertThat((List<Map<String, Object>>) body.get("set")).hasSize(1);
        assertThat(body).containsEntry("remove", List.of("X-Drop"));
    }

    @Test
    void revert_reads_spec_and_status_parents() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("parentRefs", List.of(Map.of("name", "gw", "namespace", "infra")));
        spec.put("hostnames", List.of("a.example.com"));
        spec.put("rules", List.of(Map.of(
                "matches", List.of(Map.of("path", Map.of("type", "Exact", "value", "/x"),
                        "method", "GET",
                        "headers", List.of(Map.of("name", "X", "value", "1")))),
                "backendRefs", List.of(Map.of("name", "svc", "port", 80, "weight", 5)))));
        GenericKubernetesResource res = live(spec);
        res.setAdditionalProperty("status", Map.of("parents", List.of(Map.of(
                "parentRef", Map.of("name", "gw"),
                "controllerName", "istio.io/gateway-controller",
                "conditions", List.of(Map.of("type", "Accepted", "status", "True"))))));

        HttpRouteDTO dto = c.revert(res);

        assertThat(dto.getName()).isEqualTo("route");
        assertThat(dto.getParentRefs()).hasSize(1);
        assertThat(dto.getParentRefs().get(0).getNamespace()).isEqualTo("infra");
        assertThat(dto.getHostnames()).containsExactly("a.example.com");
        HttpRouteDTO.Rule r = dto.getRules().get(0);
        assertThat(r.getMatches().get(0).getPath().getType()).isEqualTo("Exact");
        assertThat(r.getMatches().get(0).getMethod()).isEqualTo("GET");
        assertThat(r.getMatches().get(0).getHeaders().get(0).getName()).isEqualTo("X");
        assertThat(r.getBackendRefs().get(0).getWeight()).isEqualTo(5);
        assertThat(dto.getParentStatuses()).hasSize(1);
        assertThat(dto.getParentStatuses().get(0).getControllerName()).isEqualTo("istio.io/gateway-controller");
        assertThat(dto.getParentStatuses().get(0).getConditions().get(0).getType()).isEqualTo("Accepted");
    }

    @Test
    void revert_null_resource_returns_empty_dto() {
        HttpRouteDTO dto = c.revert(null);
        assertThat(dto.getName()).isNull();
        assertThat(dto.getRules()).isNull();
    }

    // ==================== fetch-overlay（本批核心） ====================

    @Test
    @SuppressWarnings("unchecked")
    void convertForUpdate_keeps_unmodeled_rule_fields_and_overwrites_modeled_ones() {
        // 线上 rule：未建模的 timeouts / retry / sessionPersistence + 建模字段的旧值
        Map<String, Object> liveRule = new LinkedHashMap<>();
        liveRule.put("name", "r1");
        liveRule.put("matches", List.of(Map.of("path", Map.of("type", "PathPrefix", "value", "/old"))));
        liveRule.put("backendRefs", List.of(Map.of("name", "old-svc", "port", 80)));
        liveRule.put("timeouts", Map.of("request", "30s"));
        liveRule.put("retry", Map.of("attempts", 3));
        liveRule.put("sessionPersistence", Map.of("sessionName", "sess"));
        Map<String, Object> liveSpec = new LinkedHashMap<>();
        liveSpec.put("hostnames", List.of("old.example.com"));
        liveSpec.put("rules", List.of(liveRule));
        liveSpec.put("useDefaultGateways", "All"); // v1.6 未建模 spec 键

        HttpRouteDTO.Rule dtoRule = rule(backend("new-svc", 9090));
        dtoRule.setName("r1");
        ((HttpRouteDTO.PathMatch) dtoRule.getMatches().get(0).getPath()).setValue("/new");
        HttpRouteDTO dto = sample();
        dto.setRules(List.of(dtoRule));
        dto.setHostnames(List.of("new.example.com"));

        Map<String, Object> spec = specOf(c.convertForUpdate(dto, live(liveSpec)));

        // spec 级：建模键覆盖 + 未建模键保留
        assertThat(spec).containsEntry("hostnames", List.of("new.example.com"))
                .containsEntry("useDefaultGateways", "All");

        Map<String, Object> outRule = rulesOf(spec).get(0);
        // 未建模 rule 级字段原样保留（SSA 的 atomic list 会抹掉它们，overlay 才行）
        assertThat(outRule).containsEntry("timeouts", Map.of("request", "30s"))
                .containsEntry("retry", Map.of("attempts", 3))
                .containsEntry("sessionPersistence", Map.of("sessionName", "sess"));
        // 建模字段被覆盖
        assertThat((Map<String, Object>) ((List<Map<String, Object>>) outRule.get("matches")).get(0).get("path"))
                .containsEntry("value", "/new");
        assertThat((List<Map<String, Object>>) outRule.get("backendRefs")).hasSize(1);
        assertThat(((List<Map<String, Object>>) outRule.get("backendRefs")).get(0)).containsEntry("name", "new-svc");
    }

    @Test
    @SuppressWarnings("unchecked")
    void convertForUpdate_preserves_unmodeled_filter_element_and_drops_removed_modeled_one() {
        // 线上 filters：CORS（未建模 type）+ RequestRedirect（建模 type，表单里被移除）
        Map<String, Object> liveCors = Map.of("type", "CORS", "cors", Map.of("allowOrigins", List.of("*")));
        Map<String, Object> liveRedirect = Map.of("type", "RequestRedirect",
                "requestRedirect", Map.of("scheme", "https", "statusCode", 301));
        Map<String, Object> liveRule = new LinkedHashMap<>();
        liveRule.put("filters", List.of(liveCors, liveRedirect));
        liveRule.put("backendRefs", List.of(Map.of("name", "svc", "port", 80)));
        Map<String, Object> liveSpec = new LinkedHashMap<>();
        liveSpec.put("rules", List.of(liveRule));

        // 表单只给 RequestHeaderModifier（新增）；RequestRedirect 被移除；CORS 平台不认识
        HttpRouteDTO.Filter dtoFilter = new HttpRouteDTO.Filter();
        dtoFilter.setType("RequestHeaderModifier");
        HttpRouteDTO.HeaderFilter hf = new HttpRouteDTO.HeaderFilter();
        hf.setRemove(List.of("X-Internal"));
        dtoFilter.setRequestHeaderModifier(hf);
        HttpRouteDTO.Rule dtoRule = rule(backend("svc", 80));
        dtoRule.setFilters(List.of(dtoFilter));
        HttpRouteDTO dto = sample();
        dto.setRules(List.of(dtoRule));

        List<Map<String, Object>> filters = (List<Map<String, Object>>) rulesOf(specOf(c.convertForUpdate(dto, live(liveSpec))))
                .get(0).get("filters");

        // CORS 整元素保留、RequestRedirect 删除、RequestHeaderModifier 新增
        assertThat(filters).extracting(f -> f.get("type"))
                .containsExactly("CORS", "RequestHeaderModifier");
        assertThat(filters.get(0)).containsEntry("cors", Map.of("allowOrigins", List.of("*")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void convertForUpdate_overlays_matches_by_index_keeping_unmodeled_sibling_keys() {
        Map<String, Object> liveMatch = new LinkedHashMap<>();
        liveMatch.put("path", Map.of("type", "PathPrefix", "value", "/old"));
        liveMatch.put("someFutureMatchField", "keep-me");
        Map<String, Object> liveRule = new LinkedHashMap<>();
        liveRule.put("matches", List.of(liveMatch));
        liveRule.put("backendRefs", List.of(Map.of("name", "svc", "port", 80)));
        Map<String, Object> liveSpec = new LinkedHashMap<>();
        liveSpec.put("rules", List.of(liveRule));

        HttpRouteDTO.Rule dtoRule = rule(backend("svc", 80));
        ((HttpRouteDTO.PathMatch) dtoRule.getMatches().get(0).getPath()).setValue("/new");
        HttpRouteDTO dto = sample();
        dto.setRules(List.of(dtoRule));

        Map<String, Object> match = ((List<Map<String, Object>>) rulesOf(specOf(c.convertForUpdate(dto, live(liveSpec))))
                .get(0).get("matches")).get(0);

        assertThat(match).containsEntry("someFutureMatchField", "keep-me");
        assertThat((Map<String, Object>) match.get("path")).containsEntry("value", "/new");
    }

    @Test
    @SuppressWarnings("unchecked")
    void convertForUpdate_can_clear_parentRefs_and_hostnames() {
        Map<String, Object> liveSpec = new LinkedHashMap<>();
        liveSpec.put("parentRefs", List.of(Map.of("name", "gw")));
        liveSpec.put("hostnames", List.of("a.example.com"));
        liveSpec.put("rules", List.of(Map.of("backendRefs", List.of(Map.of("name", "svc", "port", 80)))));

        HttpRouteDTO dto = new HttpRouteDTO();
        dto.setName("route");
        dto.setNamespace("team-a");
        dto.setParentRefs(null);
        dto.setHostnames(null);
        dto.setRules(List.of(rule(backend("svc", 80))));

        Map<String, Object> spec = specOf(c.convertForUpdate(dto, live(liveSpec)));

        assertThat(spec).doesNotContainKeys("parentRefs", "hostnames");
    }

    @Test
    @SuppressWarnings("unchecked")
    void convertForUpdate_new_rule_has_no_leftover_from_live() {
        Map<String, Object> liveRule = new LinkedHashMap<>();
        liveRule.put("backendRefs", List.of(Map.of("name", "svc", "port", 80)));
        liveRule.put("timeouts", Map.of("request", "30s"));
        Map<String, Object> liveSpec = new LinkedHashMap<>();
        liveSpec.put("rules", List.of(liveRule));

        // 表单两条 rule，线上只有一条 → 第二条全新构建，不该继承线上第一条的 timeouts
        HttpRouteDTO dto = sample();
        dto.setRules(List.of(rule(backend("svc", 80)), rule(backend("svc2", 8081))));

        List<Map<String, Object>> rules = rulesOf(specOf(c.convertForUpdate(dto, live(liveSpec))));

        assertThat(rules).hasSize(2);
        assertThat(rules.get(0)).containsEntry("timeouts", Map.of("request", "30s"));
        assertThat(rules.get(1)).doesNotContainKey("timeouts");
    }

}
