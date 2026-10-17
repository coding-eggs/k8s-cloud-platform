package com.coding.platformapi.metrics;

import com.coding.common.models.k8s.dto.PrometheusRuleDTO;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对齐算法。判据是「拿平台手上的 (namespace, CR名) 清单去匹配 Prometheus 的 file」，
 * **不是**去解析那个 file 字符串 —— 命名空间和 CR 名都含 `-`，盲解析必错。
 */
class RuleRuntimeMatcherTest {

    private static final Duration GRACE = Duration.ofMinutes(5);

    private static PrometheusRuleDTO rule(String ns, String name, String createdAt, String group, String... alertNames) {
        PrometheusRuleDTO dto = new PrometheusRuleDTO();
        dto.setNamespace(ns);
        dto.setName(name);
        dto.setCreationTime(createdAt);
        PrometheusRuleDTO.Group g = new PrometheusRuleDTO.Group();
        g.setName(group);
        g.setRules(java.util.Arrays.stream(alertNames).map(n -> {
            PrometheusRuleDTO.Rule r = new PrometheusRuleDTO.Rule();
            r.setAlert(n);
            return r;
        }).toList());
        dto.setGroups(List.of(g));
        return dto;
    }

    private static String file(String ns, String crName) {
        return "/etc/prometheus/rules/prometheus-ps-…-rulefiles-0/" + ns + "-" + crName + "-21737e75-fee9-4d9c-a94d-7bc8b5bd87c8.yaml";
    }

    private static PromRulesClient.RuleGroup group(String name, String file, PromRulesClient.RuleView... rules) {
        return new PromRulesClient.RuleGroup(name, file, List.of(rules));
    }

    private static PromRulesClient.RuleView alert(String name, String health, String state, String lastError, Integer active) {
        return new PromRulesClient.RuleView(name, health, lastError, state, active);
    }

    /**
     * ⚠️ **本批最容易错的一条**：命名空间和 CR 名都含 `-`。
     * my-app + rules → file 前缀是 `my-app-rules-`，按第一个 `-` 切会切出 ns=`my`（错）。
     * 正确做法是拿候选集去匹配，不是解析。
     */
    @Test
    void a_namespace_and_cr_name_that_both_contain_dashes_still_match() {
        var groups = List.of(group("app", file("my-app", "rules"),
                alert("HighCPU", "ok", "firing", null, 3)));

        var runtime = RuleRuntimeMatcher.match(groups, List.of(rule("my-app", "rules", "2026-10-17T00:00:00Z", "app", "HighCPU")), GRACE);

        assertThat(runtime.getLoadState()).isEqualTo("loaded");
        assertThat(runtime.getRules()).hasSize(1);
        assertThat(runtime.getRules().get(0).getState()).isEqualTo("firing");
        assertThat(runtime.getRules().get(0).getActiveAlerts()).isEqualTo(3);
    }

    /**
     * ⚠️ **前缀不是单射**：`ns=a` + `name=b-c` 与 `ns=a-b` + `name=c` 拼出来都是 `a-b-c-`。
     * 这种时候**两份都不能认领**那些文件 —— 猜错就是把 A 的运行状态挂到 B 身上。
     * 诚实做法：整份标 unknown（"读到了，但对不上账"），而不是随便挑一个。
     */
    @Test
    void an_ambiguous_file_prefix_makes_both_rules_unknown_not_guessed() {
        var groups = List.of(group("app", file("a", "b-c"), alert("X", "ok", "firing", null, 1)));

        var runtime = RuleRuntimeMatcher.match(groups, List.of(
                rule("a", "b-c", "2026-10-17T00:00:00Z", "app", "X"),
                rule("a-b", "c", "2026-10-17T00:00:00Z", "app", "X")), GRACE);

        assertThat(runtime.getLoadState()).isEqualTo("unknown");
        assertThat(runtime.getRules()).isEmpty();
    }

    /** 只有一个 CR 时，同一个文件照常认领（歧义判定不该误伤正常情形）。 */
    @Test
    void a_single_candidate_with_that_prefix_is_claimed_normally() {
        var groups = List.of(group("app", file("a", "b-c"), alert("X", "ok", "firing", null, 1)));

        var runtime = RuleRuntimeMatcher.match(groups,
                List.of(rule("a", "b-c", "2026-10-17T00:00:00Z", "app", "X")), GRACE);

        assertThat(runtime.getLoadState()).isEqualTo("loaded");
        assertThat(runtime.getRules().get(0).getState()).isEqualTo("firing");
    }

    /**
     * 前缀必须**跟在一个 `/` 后面**才算匹配。
     * <p>CR `ns=b / name=c` 的前缀是 `b-c-`，而 CR `ns=a / name=b-c` 的文件名是
     * `…/a-b-c-uuid.yaml` —— **它含 `b-c-` 但不含 `/b-c-`**。去掉前导斜杠就把它错认领了。
     */
    @Test
    void a_shorter_prefix_does_not_steal_a_longer_ones_file() {
        var groups = List.of(group("app", file("a", "b-c"), alert("X", "ok", "firing", null, 1)));

        // 输入顺序：b/c 在前、a/b-c 在后 —— 所以只认领成功的那一份（index 1）才有 state
        var runtime = RuleRuntimeMatcher.match(groups, List.of(
                rule("b", "c", "2026-10-17T00:00:00Z", "app", "X"),        // ← 前缀 b-c-，不该认领
                rule("a", "b-c", "2026-10-17T00:00:00Z", "app", "X")), GRACE);   // ← 真正的主人

        assertThat(runtime.getLoadState()).isEqualTo("loaded");
        assertThat(runtime.getRules().get(0).getHealth()).isEqualTo("unknown");
        assertThat(runtime.getRules().get(1).getState()).isEqualTo("firing");
    }

    /** 一条 CR 里的多个组都要对齐上。 */
    @Test
    void multiple_groups_of_one_cr_all_match() {
        var groups = List.of(
                group("app", file("ns1", "r1"), alert("A", "ok", "inactive", null, 0)),
                group("infra", file("ns1", "r1"), alert("B", "ok", "pending", null, 1)));

        var runtime = RuleRuntimeMatcher.match(groups,
                List.of(rule("ns1", "r1", "2026-10-17T00:00:00Z", "app", "A"),
                        rule("ns1", "r1", "2026-10-17T00:00:00Z", "infra", "B")), GRACE);

        assertThat(runtime.getRules()).hasSize(2);
        assertThat(runtime.getRules().get(0).getGroup()).isEqualTo("app");
        assertThat(runtime.getRules().get(0).getState()).isEqualTo("inactive");
        assertThat(runtime.getRules().get(1).getGroup()).isEqualTo("infra");
        assertThat(runtime.getRules().get(1).getState()).isEqualTo("pending");
    }

    /** recording 规则没有 state / activeAlerts —— 归一后必须是 null，不能是 "unknown" 或 0。 */
    @Test
    void recording_rules_carry_no_state() {
        var groups = List.of(group("rec", file("ns1", "r1"),
                new PromRulesClient.RuleView("job:rate5m", "ok", null, null, null)));

        // 构造一条 record 规则（rule() 助手只造 alert，这里手工建）
        PrometheusRuleDTO cr = new PrometheusRuleDTO();
        cr.setNamespace("ns1");
        cr.setName("r1");
        cr.setCreationTime("2026-10-17T00:00:00Z");
        PrometheusRuleDTO.Rule rec = new PrometheusRuleDTO.Rule();
        rec.setRecord("job:rate5m");
        PrometheusRuleDTO.Group g = new PrometheusRuleDTO.Group();
        g.setName("rec");
        g.setRules(List.of(rec));
        cr.setGroups(List.of(g));

        var runtime = RuleRuntimeMatcher.match(groups, List.of(cr), GRACE);

        assertThat(runtime.getRules().get(0).getName()).isEqualTo("job:rate5m");
        assertThat(runtime.getRules().get(0).getHealth()).isEqualTo("ok");
        assertThat(runtime.getRules().get(0).getState()).isNull();
        assertThat(runtime.getRules().get(0).getActiveAlerts()).isNull();
    }

    /** CR 里有一条规则而 Prometheus 里没有 → 那一条是 unknown（不是整份 missing）。 */
    @Test
    void a_single_unmatched_rule_is_unknown_not_missing() {
        var groups = List.of(group("app", file("ns1", "r1"), alert("A", "ok", "inactive", null, 0)));

        var runtime = RuleRuntimeMatcher.match(groups,
                List.of(rule("ns1", "r1", "2026-10-17T00:00:00Z", "app", "A", "Ghost")), GRACE);

        assertThat(runtime.getLoadState()).isEqualTo("loaded");
        assertThat(runtime.getRules()).hasSize(2);
        assertThat(runtime.getRules().get(0).getHealth()).isEqualTo("ok");
        assertThat(runtime.getRules().get(1).getName()).isEqualTo("Ghost");
        assertThat(runtime.getRules().get(1).getHealth()).isEqualTo("unknown");
    }

    /** health=err 时把 lastError 原文带出来（这是"expr 写错了"唯一能看见的地方）。 */
    @Test
    void an_err_health_carries_the_last_error_verbatim() {
        var groups = List.of(group("app", file("ns1", "r1"),
                alert("Bad", "err", null, "parse error at char 5: unexpected end of input", null)));

        var runtime = RuleRuntimeMatcher.match(groups,
                List.of(rule("ns1", "r1", "2026-10-17T00:00:00Z", "app", "Bad")), GRACE);

        assertThat(runtime.getRules().get(0).getHealth()).isEqualTo("err");
        assertThat(runtime.getRules().get(0).getLastError()).contains("unexpected end of input");
    }

    /** 过了宽限期还找不到 → missing（这就是「规则没被拾取」的信号）。 */
    @Test
    void not_found_after_the_grace_period_is_missing() {
        var runtime = RuleRuntimeMatcher.match(List.of(),
                List.of(rule("ns1", "r1", Instant.now().minus(Duration.ofHours(1)).toString(), "app", "A")), GRACE);

        assertThat(runtime.getLoadState()).isEqualTo("missing");
    }

    /** 宽限期内找不到 → 也是 loaded（"还没 reload"，不是 missing）。 */
    @Test
    void not_found_within_the_grace_period_is_not_missing() {
        var runtime = RuleRuntimeMatcher.match(List.of(),
                List.of(rule("ns1", "r1", Instant.now().minus(Duration.ofSeconds(30)).toString(), "app", "A")), GRACE);

        assertThat(runtime.getLoadState()).isEqualTo("loaded");
        assertThat(runtime.getRules()).isEmpty();
    }

    /** creationTime 解析不了 → 按"老对象"处理（宁可报 missing 也别静默放过）。 */
    @Test
    void an_unparseable_creation_time_is_treated_as_old() {
        var runtime = RuleRuntimeMatcher.match(List.of(),
                List.of(rule("ns1", "r1", "not-a-timestamp", "app", "A")), GRACE);

        assertThat(runtime.getLoadState()).isEqualTo("missing");
    }

    /** 一个 CR 都没给 → loaded + 空（不是 missing）。 */
    @Test
    void no_rules_at_all_is_loaded() {
        assertThat(RuleRuntimeMatcher.match(List.of(), List.of(), GRACE).getLoadState()).isEqualTo("loaded");
    }
}
