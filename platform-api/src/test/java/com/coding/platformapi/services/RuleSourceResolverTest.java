package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.PrometheusRuleDTO;
import com.coding.data.models.k8s.AlertSource;
import com.coding.data.models.k8s.PlatformAlertEvent;
import com.coding.platformapi.k8s.K8sClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RuleSourceResolverTest {

    private static PlatformAlertEvent event(String ns, String alert) {
        PlatformAlertEvent e = new PlatformAlertEvent();
        e.setNamespace(ns);
        e.setAlertname(alert);
        return e;
    }

    private static PrometheusRuleDTO cr(String ns, String name, String group, String alert) {
        PrometheusRuleDTO d = new PrometheusRuleDTO();
        d.setNamespace(ns);
        d.setName(name);
        PrometheusRuleDTO.Rule r = new PrometheusRuleDTO.Rule();
        r.setAlert(alert);
        PrometheusRuleDTO.Group g = new PrometheusRuleDTO.Group();
        g.setName(group);
        g.setRules(List.of(r));
        d.setGroups(List.of(g));
        return d;
    }

    @Test
    void resolves_by_namespace_and_alertname() {
        K8sClient k8s = mock(K8sClient.class);
        RuleSourceResolver resolver = new RuleSourceResolver(k8s);
        doReturn(List.of(cr("mon-app", "app-rules", "app", "HighCPU"))).when(k8s).list(any());

        var map = resolver.resolve("c1", "t1", List.of(event("mon-app", "HighCPU")));

        assertThat(map.get(RuleSourceResolver.key("mon-app", "HighCPU")))
                .containsExactly(new AlertSource("app-rules", "app"));
    }

    /** 同一 CR/组重复定义同名规则时去重。 */
    @Test
    void duplicate_definitions_are_collapsed() {
        K8sClient k8s = mock(K8sClient.class);
        RuleSourceResolver resolver = new RuleSourceResolver(k8s);
        PrometheusRuleDTO d = cr("ns", "cr", "g", "A");
        PrometheusRuleDTO.Rule again = new PrometheusRuleDTO.Rule();
        again.setAlert("A");
        d.getGroups().get(0).setRules(List.of(again, again));
        doReturn(List.of(d)).when(k8s).list(any());

        assertThat(resolver.resolve("c1", "t1", List.of(event("ns", "A")))
                .get(RuleSourceResolver.key("ns", "A"))).hasSize(1);
    }

    /** 管理员（tenantId 为空）走 listAll；租户走按 namespace list。 */
    @Test
    void admin_uses_list_all_and_tenant_uses_namespace_list() {
        K8sClient k8s = mock(K8sClient.class);
        RuleSourceResolver resolver = new RuleSourceResolver(k8s);
        doReturn(List.of()).when(k8s).listAll(any());
        doReturn(List.of()).when(k8s).list(any());

        resolver.resolve("c1", null, List.of(event("ns", "A")));
        verify(k8s).listAll(any());
        verify(k8s, never()).list(any());

        resolver.resolve("c1", "t1", List.of(event("ns", "A")));
        verify(k8s).list(any());
    }

    /** record 规则不产生告警，不进索引。 */
    @Test
    void record_rules_are_not_indexed() {
        K8sClient k8s = mock(K8sClient.class);
        RuleSourceResolver resolver = new RuleSourceResolver(k8s);
        PrometheusRuleDTO d = new PrometheusRuleDTO();
        d.setNamespace("ns");
        d.setName("cr");
        PrometheusRuleDTO.Rule rec = new PrometheusRuleDTO.Rule();
        rec.setRecord("job:x:rate5m");
        PrometheusRuleDTO.Group g = new PrometheusRuleDTO.Group();
        g.setName("g");
        g.setRules(List.of(rec));
        d.setGroups(List.of(g));
        doReturn(List.of(d)).when(k8s).list(any());

        assertThat(resolver.resolve("c1", "t1", List.of(event("ns", "job:x:rate5m")))).isEmpty();
    }

    /**
     * 反查本身失败 → 返回 null（"没做成反查"），不是空 map。
     * 这个区分让列表能把「来源不可知」与「规则真的没了」显示成不同东西。
     */
    @Test
    void a_failed_lookup_returns_null() {
        K8sClient k8s = mock(K8sClient.class);
        RuleSourceResolver resolver = new RuleSourceResolver(k8s);
        doThrow(new RuntimeException("k8s down")).when(k8s).list(any());

        assertThat(resolver.resolve("c1", "t1", List.of(event("ns", "A")))).isNull();
    }

    /** 拉到了规则但没有同名 alert → 空 map（查过了，就是没有）。 */
    @Test
    void a_missing_rule_yields_an_empty_map() {
        K8sClient k8s = mock(K8sClient.class);
        RuleSourceResolver resolver = new RuleSourceResolver(k8s);
        doReturn(List.of()).when(k8s).list(any());

        assertThat(resolver.resolve("c1", "t1", List.of(event("ns", "A")))).isEmpty();
    }

    /** 单条查询（投递引擎按它找绑定）：命中返回 CR 名。 */
    @Test
    void crNameOf_returns_the_owning_cr_for_one_alert() {
        K8sClient k8s = mock(K8sClient.class);
        RuleSourceResolver resolver = new RuleSourceResolver(k8s);
        doReturn(List.of(cr("mon-app", "app-rules", "app", "HighCPU"))).when(k8s).list(any());

        assertThat(resolver.crNameOf("c1", "t1", event("mon-app", "HighCPU"))).isEqualTo("app-rules");
    }

    /** 单条查询查不到 → null（调用方据此回落到默认收件人）。 */
    @Test
    void crNameOf_returns_null_when_the_rule_is_gone() {
        K8sClient k8s = mock(K8sClient.class);
        RuleSourceResolver resolver = new RuleSourceResolver(k8s);
        doReturn(List.of()).when(k8s).list(any());

        assertThat(resolver.crNameOf("c1", "t1", event("mon-app", "Gone"))).isNull();
    }
}
