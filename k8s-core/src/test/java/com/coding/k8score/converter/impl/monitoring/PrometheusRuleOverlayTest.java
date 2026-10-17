package com.coding.k8score.converter.impl.monitoring;

import com.coding.common.models.k8s.dto.PrometheusRuleDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * convertForUpdate 的核心承诺：**改一条规则不会碰掉别的未建模字段**。
 * spec.groups 与 group.rules 都是 atomic list，SSA 会把整个列表当一个值。
 */
class PrometheusRuleOverlayTest {

    private final PrometheusRuleConverter c = new PrometheusRuleConverter();

    private static GenericKubernetesResource liveWithUnmodeledFields() {
        // 线上：组上有 CRD 未来版本才有的自定义键；规则上有平台不建模的兄弟键
        Map<String, Object> ruleA = new LinkedHashMap<>();
        ruleA.put("alert", "HighCPU");
        ruleA.put("expr", "up == 0");
        ruleA.put("someFutureField", "keep-me");

        Map<String, Object> ruleB = new LinkedHashMap<>();
        ruleB.put("alert", "LowDisk");
        ruleB.put("expr", "disk_free < 1");

        Map<String, Object> group = new LinkedHashMap<>();
        group.put("name", "app");
        group.put("interval", "30s");
        group.put("futureGroupField", "keep-me-too");
        group.put("rules", List.of(ruleA, ruleB));

        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setApiVersion("monitoring.coreos.com/v1");
        res.setKind("PrometheusRule");
        res.setMetadata(new ObjectMetaBuilder().withName("app-rules").withNamespace("my-app").build());
        res.setAdditionalProperty("spec", Map.of("groups", List.of(group)));
        return res;
    }

    /** 改 HighCPU 的 expr，不能碰掉它自己的 someFutureField，也不能碰掉组上的 futureGroupField。 */
    @Test
    void updating_one_rule_keeps_unmodeled_fields_on_the_same_rule() {
        PrometheusRuleDTO dto = new PrometheusRuleDTO();
        dto.setName("app-rules");
        dto.setNamespace("my-app");
        PrometheusRuleDTO.Rule a = new PrometheusRuleDTO.Rule();
        a.setAlert("HighCPU");
        a.setExpr("up == 1");                 // ← 改了
        PrometheusRuleDTO.Rule b = new PrometheusRuleDTO.Rule();
        b.setAlert("LowDisk");
        b.setExpr("disk_free < 1");
        PrometheusRuleDTO.Group g = new PrometheusRuleDTO.Group();
        g.setName("app");
        g.setInterval("30s");
        g.setRules(List.of(a, b));
        dto.setGroups(List.of(g));

        GenericKubernetesResource out = c.convertForUpdate(dto, liveWithUnmodeledFields());

        Map<String, Object> group = firstGroup(out);
        assertThat(group).containsEntry("futureGroupField", "keep-me-too");
        List<Map<String, Object>> rules = rules(group);
        assertThat(rules.get(0)).containsEntry("expr", "up == 1")
                .containsEntry("someFutureField", "keep-me");
        assertThat(rules.get(1)).containsEntry("alert", "LowDisk");
    }

    /** 用户在表单上删掉一条规则 → 它必须从对象里消失（不是被"保留"）。 */
    @Test
    void a_rule_dropped_by_the_user_is_removed() {
        PrometheusRuleDTO dto = new PrometheusRuleDTO();
        dto.setName("app-rules");
        dto.setNamespace("my-app");
        PrometheusRuleDTO.Rule a = new PrometheusRuleDTO.Rule();
        a.setAlert("HighCPU");
        a.setExpr("up == 0");
        PrometheusRuleDTO.Group g = new PrometheusRuleDTO.Group();
        g.setName("app");
        g.setRules(List.of(a));               // ← LowDisk 被删了
        dto.setGroups(List.of(g));

        GenericKubernetesResource out = c.convertForUpdate(dto, liveWithUnmodeledFields());

        assertThat(rules(firstGroup(out))).hasSize(1);
    }

    /**
     * 用户新加一条规则，但**同名**的那条在线上不存在 → 全新构建，不去对齐到别人身上。
     * 这是「按名对齐」与 ServiceMonitor 的「按 index 对齐」最大的不同，必须钉住。
     */
    @Test
    void a_new_rule_is_built_fresh_not_aligned_by_position() {
        PrometheusRuleDTO dto = new PrometheusRuleDTO();
        dto.setName("app-rules");
        dto.setNamespace("my-app");
        PrometheusRuleDTO.Rule a = new PrometheusRuleDTO.Rule();
        a.setAlert("HighCPU");
        a.setExpr("up == 0");
        PrometheusRuleDTO.Rule brandNew = new PrometheusRuleDTO.Rule();
        brandNew.setAlert("FreshAlert");
        brandNew.setExpr("vector(1)");
        PrometheusRuleDTO.Group g = new PrometheusRuleDTO.Group();
        g.setName("app");
        g.setRules(List.of(a, brandNew));
        dto.setGroups(List.of(g));

        GenericKubernetesResource out = c.convertForUpdate(dto, liveWithUnmodeledFields());

        Map<String, Object> fresh = rules(firstGroup(out)).get(1);
        assertThat(fresh).containsEntry("alert", "FreshAlert");
        assertThat(fresh).doesNotContainKey("someFutureField");   // 没串到 HighCPU 的字段上
    }

    /** 用户在表单里清空了某字段 → 从对象里移除，而不是保留线上的旧值。 */
    @Test
    void a_cleared_modeled_field_is_removed_not_kept() {
        PrometheusRuleDTO dto = new PrometheusRuleDTO();
        dto.setName("app-rules");
        dto.setNamespace("my-app");
        PrometheusRuleDTO.Rule a = new PrometheusRuleDTO.Rule();
        a.setAlert("HighCPU");
        a.setExpr("up == 0");
        PrometheusRuleDTO.Group g = new PrometheusRuleDTO.Group();
        g.setName("app");
        g.setInterval(null);                  // ← 从 30s 清空
        g.setRules(List.of(a));
        dto.setGroups(List.of(g));

        GenericKubernetesResource out = c.convertForUpdate(dto, liveWithUnmodeledFields());

        assertThat(firstGroup(out)).doesNotContainKey("interval");
    }

    /** 组被整个删掉 → 从对象里消失。 */
    @Test
    void a_group_dropped_by_the_user_is_removed() {
        PrometheusRuleDTO dto = new PrometheusRuleDTO();
        dto.setName("app-rules");
        dto.setNamespace("my-app");
        PrometheusRuleDTO.Group other = new PrometheusRuleDTO.Group();
        other.setName("brand-new-group");
        PrometheusRuleDTO.Rule r = new PrometheusRuleDTO.Rule();
        r.setAlert("A");
        r.setExpr("vector(1)");
        other.setRules(List.of(r));
        dto.setGroups(List.of(other));

        GenericKubernetesResource out = c.convertForUpdate(dto, liveWithUnmodeledFields());

        List<Map<String, Object>> groups = groups(out);
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0)).containsEntry("name", "brand-new-group");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstGroup(GenericKubernetesResource res) {
        return groups(res).get(0);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> groups(GenericKubernetesResource res) {
        Map<String, Object> spec = (Map<String, Object>) res.getAdditionalProperties().get("spec");
        return (List<Map<String, Object>>) spec.get("groups");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rules(Map<String, Object> group) {
        return (List<Map<String, Object>>) group.get("rules");
    }
}
