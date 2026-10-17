package com.coding.k8score.converter.impl.monitoring;

import com.coding.common.models.k8s.dto.PrometheusRuleDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusRuleConverterTest {

    private final PrometheusRuleConverter c = new PrometheusRuleConverter();

    private static PrometheusRuleDTO sample() {
        PrometheusRuleDTO dto = new PrometheusRuleDTO();
        dto.setName("app-rules");
        dto.setNamespace("my-app");

        PrometheusRuleDTO.Rule alert = new PrometheusRuleDTO.Rule();
        alert.setAlert("HighCPU");
        alert.setExpr("rate(container_cpu_usage_seconds_total[5m]) > 0.9");
        alert.setFor_("5m");
        alert.setLabels(Map.of("severity", "warning"));
        alert.setAnnotations(Map.of("summary", "CPU 高"));

        PrometheusRuleDTO.Rule record = new PrometheusRuleDTO.Rule();
        record.setRecord("job:http_errors:rate5m");
        record.setExpr("rate(http_requests_total{code=~\"5..\"}[5m])");

        PrometheusRuleDTO.Group g = new PrometheusRuleDTO.Group();
        g.setName("app");
        g.setInterval("30s");
        g.setRules(List.of(alert, record));

        dto.setGroups(List.of(g));
        return dto;
    }

    @Test
    void convert_produces_the_crd_shape() {
        GenericKubernetesResource res = c.convert(sample());

        assertThat(res.getApiVersion()).isEqualTo("monitoring.coreos.com/v1");
        assertThat(res.getKind()).isEqualTo("PrometheusRule");
        assertThat(res.getMetadata().getName()).isEqualTo("app-rules");
        assertThat(res.getMetadata().getNamespace()).isEqualTo("my-app");

        Map<String, Object> spec = spec(res);
        List<Map<String, Object>> groups = (List<Map<String, Object>>) spec.get("groups");
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).get("name")).isEqualTo("app");
        assertThat(groups.get(0).get("interval")).isEqualTo("30s");
        assertThat(groups.get(0)).doesNotContainKey("limit");      // null 不写出

        List<Map<String, Object>> rules = (List<Map<String, Object>>) groups.get(0).get("rules");
        assertThat(rules).hasSize(2);
        assertThat(rules.get(0)).containsEntry("alert", "HighCPU")
                .containsEntry("for", "5m")                        // ← 线上键名是 for，不是 for_
                .doesNotContainKey("record");
        assertThat(rules.get(1)).containsEntry("record", "job:http_errors:rate5m")
                .doesNotContainKey("alert");
    }

    /** 全字段 round-trip：反向不能丢任何建模字段。 */
    @Test
    void revert_round_trips_every_modeled_field() {
        PrometheusRuleDTO back = c.revert(c.convert(sample()));

        assertThat(back.getName()).isEqualTo("app-rules");
        PrometheusRuleDTO.Group g = back.getGroups().get(0);
        assertThat(g.getName()).isEqualTo("app");
        assertThat(g.getInterval()).isEqualTo("30s");
        assertThat(g.getRules()).hasSize(2);
        assertThat(g.getRules().get(0).getAlert()).isEqualTo("HighCPU");
        assertThat(g.getRules().get(0).getFor_()).isEqualTo("5m");
        assertThat(g.getRules().get(0).getLabels()).containsEntry("severity", "warning");
        assertThat(g.getRules().get(0).getAnnotations()).containsEntry("summary", "CPU 高");
        assertThat(g.getRules().get(1).getRecord()).isEqualTo("job:http_errors:rate5m");
    }

    /**
     * CRD 的 expr 是 IntOrString —— 上游允许裸数字（expr: 1 等价于 vector(1)）。
     * 读回来必须转成字符串，否则 round-trip 丢类型信息。
     */
    @Test
    void revert_converts_a_bare_numeric_expr_to_string() {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setApiVersion("monitoring.coreos.com/v1");
        res.setKind("PrometheusRule");
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("alert", "Always");
        rule.put("expr", 1);                                       // ← Integer，不是 String
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("name", "g");
        group.put("rules", List.of(rule));
        res.setAdditionalProperty("spec", Map.of("groups", List.of(group)));

        PrometheusRuleDTO dto = c.revert(res);

        assertThat(dto.getGroups().get(0).getRules().get(0).getExpr()).isEqualTo("1");
    }

    /** 没有任何 group 时不能 NPE（空对象也是合法的 K8s 响应）。 */
    @Test
    void revert_tolerates_a_missing_spec() {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setMetadata(new io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
                .withName("empty").withNamespace("ns").build());

        PrometheusRuleDTO dto = c.revert(res);

        assertThat(dto.getName()).isEqualTo("empty");
        assertThat(dto.getGroups()).isNull();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> spec(GenericKubernetesResource res) {
        return (Map<String, Object>) res.getAdditionalProperties().get("spec");
    }
}
