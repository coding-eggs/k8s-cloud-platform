package com.coding.k8score.converter.impl.gateway;

import com.coding.common.models.k8s.dto.BackendRefDTO;
import com.coding.common.models.k8s.dto.L4RouteRuleDTO;
import com.coding.common.models.k8s.dto.ParentRefDTO;
import com.coding.common.models.k8s.dto.TcpRouteDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TcpRouteConverter。两个重点：
 * <ol>
 *   <li><b>CRD 版本是构造参数</b>，只影响输出的 {@code apiVersion}（v1 与 v1alpha2 结构相同）。</li>
 *   <li>{@code rules} 里没有嵌套 atomic 子列表，故 overlay 只需保 spec 级未建模键。</li>
 * </ol>
 */
class TcpRouteConverterTest {

    private final TcpRouteConverter v1 = new TcpRouteConverter("v1");
    private final TcpRouteConverter v1alpha2 = new TcpRouteConverter("v1alpha2");

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
        res.setMetadata(new ObjectMetaBuilder().withName("tcp").withNamespace("team-a").build());
        res.setAdditionalProperty("spec", spec);
        return res;
    }

    private static TcpRouteDTO sample() {
        TcpRouteDTO dto = new TcpRouteDTO();
        dto.setName("tcp-route");
        dto.setNamespace("team-a");
        ParentRefDTO parent = new ParentRefDTO();
        parent.setName("tcp-gw");
        parent.setSectionName("tcp-3306");
        dto.setParentRefs(List.of(parent));

        BackendRefDTO a = new BackendRefDTO();
        a.setName("mysql-primary");
        a.setPort(3306);
        BackendRefDTO b = new BackendRefDTO();
        b.setName("mysql-replica");
        b.setPort(3306);
        b.setWeight(0);

        L4RouteRuleDTO rule = new L4RouteRuleDTO();
        rule.setName("r1");
        rule.setBackendRefs(List.of(a, b));
        dto.setRules(List.of(rule));
        return dto;
    }

    @Test
    void crdVersion_drives_apiVersion_only() {
        assertThat(v1.apiVersion()).isEqualTo("gateway.networking.k8s.io/v1");
        assertThat(v1alpha2.apiVersion()).isEqualTo("gateway.networking.k8s.io/v1alpha2");
        assertThat(v1.crdVersion()).isEqualTo("v1");
        assertThat(v1alpha2.crdVersion()).isEqualTo("v1alpha2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void convert_emits_kind_metadata_and_rules() {
        GenericKubernetesResource res = v1.convert(sample());

        assertThat(res.getKind()).isEqualTo("TCPRoute");
        assertThat(res.getMetadata().getNamespace()).isEqualTo("team-a");
        Map<String, Object> spec = specOf(res);
        List<Map<String, Object>> parents = (List<Map<String, Object>>) spec.get("parentRefs");
        assertThat(parents).hasSize(1);
        assertThat(parents.get(0)).containsEntry("name", "tcp-gw").containsEntry("sectionName", "tcp-3306");

        List<Map<String, Object>> rules = rulesOf(spec);
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0)).containsEntry("name", "r1");
        List<Map<String, Object>> backends = (List<Map<String, Object>>) rules.get(0).get("backendRefs");
        assertThat(backends).hasSize(2);
        assertThat(backends.get(0)).containsEntry("name", "mysql-primary").containsEntry("port", 3306);
        // weight 0 是"不转发"的合法值，必须写出来（不能用"有值才写"的规则吞掉 0）
        assertThat(backends.get(1)).containsEntry("weight", 0);
    }

    @Test
    void convert_uses_the_injected_version_for_apiVersion() {
        assertThat(v1alpha2.convert(sample()).getApiVersion()).isEqualTo("gateway.networking.k8s.io/v1alpha2");
    }

    @Test
    void revert_reads_spec_and_status_parents() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("parentRefs", List.of(Map.of("name", "tcp-gw")));
        spec.put("rules", List.of(Map.of(
                "name", "r1",
                "backendRefs", List.of(Map.of("name", "svc", "port", 3306, "weight", 3)))));
        GenericKubernetesResource res = live(spec);
        res.setAdditionalProperty("status", Map.of("parents", List.of(Map.of(
                "parentRef", Map.of("name", "tcp-gw"),
                "controllerName", "istio.io/gateway-controller",
                "conditions", List.of(Map.of("type", "Accepted", "status", "True"))))));

        TcpRouteDTO dto = v1.revert(res);

        assertThat(dto.getName()).isEqualTo("tcp");
        assertThat(dto.getNamespace()).isEqualTo("team-a");
        assertThat(dto.getParentRefs().get(0).getName()).isEqualTo("tcp-gw");
        assertThat(dto.getRules().get(0).getName()).isEqualTo("r1");
        assertThat(dto.getRules().get(0).getBackendRefs().get(0).getWeight()).isEqualTo(3);
        assertThat(dto.getParentStatuses().get(0).getConditions().get(0).getType()).isEqualTo("Accepted");
    }

    @Test
    void revert_null_resource_returns_empty_dto() {
        TcpRouteDTO dto = v1.revert(null);
        assertThat(dto.getName()).isNull();
        assertThat(dto.getRules()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void convertForUpdate_keeps_unmodeled_spec_keys_and_clears_removed_ones() {
        Map<String, Object> liveSpec = new LinkedHashMap<>();
        liveSpec.put("parentRefs", List.of(Map.of("name", "old-gw")));
        liveSpec.put("rules", List.of(Map.of("backendRefs", List.of(Map.of("name", "old-svc", "port", 3306)))));
        liveSpec.put("useDefaultGateways", "All"); // v1.6 未建模键

        // 表单清空了 parentRefs（只留 rules）
        TcpRouteDTO dto = sample();
        dto.setParentRefs(null);

        Map<String, Object> spec = specOf(v1.convertForUpdate(dto, live(liveSpec)));

        assertThat(spec).doesNotContainKey("parentRefs");            // 清空 → 移除
        assertThat(spec).containsEntry("useDefaultGateways", "All"); // 未建模键保留
        assertThat((List<Map<String, Object>>) rulesOf(spec).get(0).get("backendRefs")).hasSize(2);
    }

}
