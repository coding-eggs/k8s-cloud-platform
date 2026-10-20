package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.dto.PrometheusRuleDTO;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.platformapi.k8s.K8sClient;
import com.coding.platformapi.metrics.PromRulesClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrometheusRuleServiceTest {

    private K8sClient k8s;
    private RuleNotifyBinder binder;
    private PrometheusRuleService svc;

    @BeforeEach
    void setUp() {
        k8s = mock(K8sClient.class);
        binder = mock(RuleNotifyBinder.class);
        svc = new PrometheusRuleService(k8s, mock(K8sClusterMapper.class), mock(PromRulesClient.class), binder);
    }

    private static PrometheusRuleDTO sample(String ns) {
        PrometheusRuleDTO dto = new PrometheusRuleDTO();
        dto.setName("app-rules");
        dto.setNamespace(ns);
        PrometheusRuleDTO.Rule a = new PrometheusRuleDTO.Rule();
        a.setAlert("HighCPU");
        a.setExpr("up == 0");
        PrometheusRuleDTO.Group g = new PrometheusRuleDTO.Group();
        g.setName("app");
        g.setRules(List.of(a));
        dto.setGroups(List.of(g));
        return dto;
    }

    /** 平台代打 namespace —— Alertmanager 的 payload 里没有"规则是谁建的"，这是唯一可信的来源。 */
    @Test
    void create_stamps_the_namespace_label_on_alert_rules() {
        when(k8s.create(any())).thenAnswer(i -> i.getArgument(0));

        svc.create(sample("my-app"));

        PrometheusRuleDTO sent = captured();
        assertThat(sent.getGroups().get(0).getRules().get(0).getLabels())
                .containsEntry("namespace", "my-app");
    }

    /** 用户自己写了别的值 → 以平台的为准（否则归属可伪造）。 */
    @Test
    void create_overrides_a_user_supplied_namespace_label() {
        PrometheusRuleDTO dto = sample("my-app");
        dto.getGroups().get(0).getRules().get(0).setLabels(Map.of("namespace", "someone-else", "team", "core"));
        when(k8s.create(any())).thenAnswer(i -> i.getArgument(0));

        svc.create(dto);

        Map<String, String> labels = captured().getGroups().get(0).getRules().get(0).getLabels();
        assertThat(labels).containsEntry("namespace", "my-app")
                .containsEntry("team", "core");        // 用户其它标签不动
    }

    /** record 规则不产生告警 —— 给它打 namespace 只会污染指标标签。 */
    @Test
    void record_rules_are_not_stamped() {
        PrometheusRuleDTO dto = sample("my-app");
        PrometheusRuleDTO.Rule rec = new PrometheusRuleDTO.Rule();
        rec.setRecord("job:x:rate5m");
        rec.setExpr("vector(1)");
        dto.getGroups().get(0).setRules(List.of(rec));
        when(k8s.create(any())).thenAnswer(i -> i.getArgument(0));

        svc.create(dto);

        assertThat(captured().getGroups().get(0).getRules().get(0).getLabels()).isNull();
    }

    /** 用户其它标签在没有 namespace 时也要留着。 */
    @Test
    void existing_labels_survive_the_stamp() {
        PrometheusRuleDTO dto = sample("my-app");
        dto.getGroups().get(0).getRules().get(0).setLabels(Map.of("team", "os"));
        when(k8s.create(any())).thenAnswer(i -> i.getArgument(0));

        svc.create(dto);

        // severity 现在由下拉（平台侧字段）掌管，不再走 labels；这里验的是「其它标签不被抹掉」
        assertThat(captured().getGroups().get(0).getRules().get(0).getLabels())
                .containsEntry("team", "os")
                .containsEntry("namespace", "my-app");
    }

    /** alert 与 record 都没给 → 提交时就给人话，而不是 K8s API server 那句 must be specified。 */
    @Test
    void a_rule_with_neither_alert_nor_record_is_rejected_with_a_readable_message() {
        PrometheusRuleDTO dto = sample("my-app");
        dto.getGroups().get(0).getRules().get(0).setAlert(null);

        assertThatThrownBy(() -> svc.create(dto))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("alert")
                .hasMessageContaining("record");
    }

    /** 同时给 → 同样拒（CRD 就是 Only one of record and alert must be set）。 */
    @Test
    void a_rule_with_both_alert_and_record_is_rejected() {
        PrometheusRuleDTO dto = sample("my-app");
        dto.getGroups().get(0).getRules().get(0).setRecord("job:x:rate5m");

        assertThatThrownBy(() -> svc.create(dto))
                .isInstanceOf(CloudPlatformException.class);
    }

    /** expr 为空 → 拒（CRD 必填），而不是让 API server 拒。 */
    @Test
    void a_rule_without_expr_is_rejected() {
        PrometheusRuleDTO dto = sample("my-app");
        dto.getGroups().get(0).getRules().get(0).setExpr("  ");

        assertThatThrownBy(() -> svc.create(dto)).isInstanceOf(CloudPlatformException.class);
    }

    /** 组名为空 → 拒。 */
    @Test
    void a_group_without_name_is_rejected() {
        PrometheusRuleDTO dto = sample("my-app");
        dto.getGroups().get(0).setName(null);

        assertThatThrownBy(() -> svc.create(dto)).isInstanceOf(CloudPlatformException.class);
    }

    /** update 走同一条代打路径（否则改一次规则归属就丢了）。 */
    @Test
    void update_also_stamps() {
        when(k8s.update(any())).thenAnswer(i -> i.getArgument(0));

        svc.update(sample("my-app"));

        PrometheusRuleDTO sent = capturedUpdate();
        assertThat(sent.getGroups().get(0).getRules().get(0).getLabels())
                .containsEntry("namespace", "my-app");
    }

    /** 校验失败必须是本批新加的码，不能借道别的码。 */
    @Test
    void validation_uses_the_dedicated_response_code() {
        PrometheusRuleDTO dto = sample("my-app");
        dto.getGroups().get(0).getRules().get(0).setAlert(null);

        assertThatThrownBy(() -> svc.create(dto))
                .isInstanceOfSatisfying(CloudPlatformException.class,
                        e -> assertThat(e.getCode()).isEqualTo(10045));
    }

    // ---------- 时长字段格式（apiserver 422 的前置拦截）----------

    /**
     * ⚠️ 2026-10-20 真实踩到：interval 写成纯数字 "30"，apiserver 直接 422，
     * 回给前端的是 fabric8 的 KubernetesClientException + 一坨 Status。
     * 平台必须先拦，报「要写成 30s」。
     */
    @Test
    void a_bare_number_interval_is_rejected_before_hitting_the_cluster() {
        PrometheusRuleDTO dto = sample("my-app");
        dto.getGroups().get(0).setInterval("30");

        assertThatThrownBy(() -> svc.create(dto))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("interval")
                .hasMessageContaining("30s");

        verify(k8s, never()).create(any());
    }

    /** for 同样：只写秒数没有单位也拦。 */
    @Test
    void a_bare_number_for_is_rejected() {
        PrometheusRuleDTO dto = sample("my-app");
        dto.getGroups().get(0).getRules().get(0).setFor_("300");

        assertThatThrownBy(() -> svc.create(dto))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("for");

        verify(k8s, never()).create(any());
    }

    /** 带单位的合法写法（含 0、复合时长、留空）照常放行。 */
    @Test
    void valid_durations_pass_through() {
        PrometheusRuleDTO dto = sample("my-app");
        dto.getGroups().get(0).setInterval("30s");
        dto.getGroups().get(0).getRules().get(0).setFor_("5m");
        dto.getGroups().get(0).getRules().get(0).setKeepFiringFor("1h30m");
        when(k8s.create(any())).thenAnswer(i -> i.getArgument(0));

        svc.create(dto);

        verify(k8s).create(any());
    }

    // ---------- 严重度（下拉 → labels.severity）----------

    /** 编辑页严重度下拉是平台侧字段：保存时拼成 Prometheus 约定的 labels.severity。 */
    @Test
    void create_stamps_the_severity_into_labels() {
        PrometheusRuleDTO dto = sample("my-app");
        dto.getGroups().get(0).getRules().get(0).setSeverity("critical");
        when(k8s.create(any())).thenAnswer(i -> i.getArgument(0));

        svc.create(dto);

        PrometheusRuleDTO sent = captured();
        assertThat(sent.getGroups().get(0).getRules().get(0).getLabels())
                .containsEntry("severity", "critical");
        // 平台侧字段不下发（K8s 那边只该看到 labels.severity 这一份）
        assertThat(sent.getGroups().get(0).getRules().get(0).getSeverity()).isNull();
    }

    /** 下拉被清空 = 删掉 labels.severity（不能留着旧值让用户以为改了）。 */
    @Test
    void a_cleared_severity_removes_the_label() {
        PrometheusRuleDTO dto = sample("my-app");
        PrometheusRuleDTO.Rule rule = dto.getGroups().get(0).getRules().get(0);
        rule.setLabels(new java.util.LinkedHashMap<>(Map.of("severity", "warning", "team", "os")));
        rule.setSeverity(null);
        when(k8s.create(any())).thenAnswer(i -> i.getArgument(0));

        svc.create(dto);

        assertThat(captured().getGroups().get(0).getRules().get(0).getLabels())
                .doesNotContainKey("severity")
                .containsEntry("team", "os");
    }

    /** 读详情时把 labels.severity 摘回平台侧字段，编辑页的下拉才有值。 */
    @Test
    void get_fills_the_severity_back_from_labels() {
        PrometheusRuleDTO stored = sample("my-app");
        stored.getGroups().get(0).getRules().get(0)
                .setLabels(new java.util.LinkedHashMap<>(Map.of("severity", "warning")));
        when(k8s.get(any())).thenReturn(stored);

        PrometheusRuleDTO out = svc.get("app-rules", "t1", "c1", "my-app");

        assertThat(out.getGroups().get(0).getRules().get(0).getSeverity()).isEqualTo("warning");
    }

    /** 摘要输入框是平台侧字段：保存时拼成 annotations.summary。 */
    @Test
    void create_stamps_the_summary_into_annotations() {
        PrometheusRuleDTO dto = sample("my-app");
        dto.getGroups().get(0).getRules().get(0).setSummary("实例挂了");
        when(k8s.create(any())).thenAnswer(i -> i.getArgument(0));

        svc.create(dto);

        PrometheusRuleDTO sent = captured();
        assertThat(sent.getGroups().get(0).getRules().get(0).getAnnotations())
                .containsEntry("summary", "实例挂了")
                .containsEntry("platform_rule", "app-rules");
        // 平台侧字段不下发
        assertThat(sent.getGroups().get(0).getRules().get(0).getSummary()).isNull();
    }

    /** 清空摘要 = 删掉 annotations.summary，但 platform_rule 要留着（投递靠它找绑定）。 */
    @Test
    void a_cleared_summary_removes_only_the_summary_annotation() {
        PrometheusRuleDTO dto = sample("my-app");
        PrometheusRuleDTO.Rule rule = dto.getGroups().get(0).getRules().get(0);
        rule.setAnnotations(new java.util.LinkedHashMap<>(Map.of("summary", "旧的", "team", "os")));
        rule.setSummary(null);
        when(k8s.create(any())).thenAnswer(i -> i.getArgument(0));

        svc.create(dto);

        // summary / description / runbook_url 都已归平台侧字段管，这里用普通注解验「其它注解不被抹掉」
        assertThat(captured().getGroups().get(0).getRules().get(0).getAnnotations())
                .doesNotContainKey("summary")
                .containsEntry("team", "os")
                .containsEntry("platform_rule", "app-rules");
    }

    /** 读详情时把 annotations.summary 摘回平台侧字段，编辑页的摘要输入框才有值。 */
    @Test
    void get_fills_the_summary_back_from_annotations() {
        PrometheusRuleDTO stored = sample("my-app");
        stored.getGroups().get(0).getRules().get(0)
                .setAnnotations(new java.util.LinkedHashMap<>(Map.of("summary", "实例挂了")));
        when(k8s.get(any())).thenReturn(stored);

        PrometheusRuleDTO out = svc.get("app-rules", "t1", "c1", "my-app");

        assertThat(out.getGroups().get(0).getRules().get(0).getSummary()).isEqualTo("实例挂了");
    }

    /** 详情输入框同样是平台侧字段：保存时拼成 annotations.description。 */
    @Test
    void create_stamps_the_description_into_annotations() {
        PrometheusRuleDTO dto = sample("my-app");
        dto.getGroups().get(0).getRules().get(0).setDescription("实例连续 5 分钟不可达，当前 up=0");
        when(k8s.create(any())).thenAnswer(i -> i.getArgument(0));

        svc.create(dto);

        assertThat(captured().getGroups().get(0).getRules().get(0).getAnnotations())
                .containsEntry("description", "实例连续 5 分钟不可达，当前 up=0");
    }

    /** 清空详情 = 删掉 annotations.description，但 summary / platform_rule 不受影响。 */
    @Test
    void a_cleared_description_removes_only_the_description_annotation() {
        PrometheusRuleDTO dto = sample("my-app");
        PrometheusRuleDTO.Rule rule = dto.getGroups().get(0).getRules().get(0);
        rule.setSummary("实例挂了");
        rule.setAnnotations(new java.util.LinkedHashMap<>(Map.of("description", "旧的")));
        rule.setDescription(null);
        when(k8s.create(any())).thenAnswer(i -> i.getArgument(0));

        svc.create(dto);

        assertThat(captured().getGroups().get(0).getRules().get(0).getAnnotations())
                .doesNotContainKey("description")
                .containsEntry("summary", "实例挂了")
                .containsEntry("platform_rule", "app-rules");
    }

    /** 读详情时把 annotations.description 摘回平台侧字段。 */
    @Test
    void get_fills_the_description_back_from_annotations() {
        PrometheusRuleDTO stored = sample("my-app");
        stored.getGroups().get(0).getRules().get(0)
                .setAnnotations(new java.util.LinkedHashMap<>(Map.of("description", "详细说明")));
        when(k8s.get(any())).thenReturn(stored);

        PrometheusRuleDTO out = svc.get("app-rules", "t1", "c1", "my-app");

        assertThat(out.getGroups().get(0).getRules().get(0).getDescription()).isEqualTo("详细说明");
    }

    /** 排障链接同样是平台侧字段：保存时拼成 annotations.runbook_url（惯例键，带下划线）。 */
    @Test
    void create_stamps_the_runbook_url_into_annotations() {
        PrometheusRuleDTO dto = sample("my-app");
        dto.getGroups().get(0).getRules().get(0).setRunbookUrl("https://runbooks/HighCPU");
        when(k8s.create(any())).thenAnswer(i -> i.getArgument(0));

        svc.create(dto);

        assertThat(captured().getGroups().get(0).getRules().get(0).getAnnotations())
                .containsEntry("runbook_url", "https://runbooks/HighCPU");
    }

    /** 清空链接 = 删掉 annotations.runbook_url。 */
    @Test
    void a_cleared_runbook_url_removes_the_annotation() {
        PrometheusRuleDTO dto = sample("my-app");
        PrometheusRuleDTO.Rule rule = dto.getGroups().get(0).getRules().get(0);
        rule.setAnnotations(new java.util.LinkedHashMap<>(Map.of("runbook_url", "https://old")));
        rule.setRunbookUrl(null);
        when(k8s.create(any())).thenAnswer(i -> i.getArgument(0));

        svc.create(dto);

        assertThat(captured().getGroups().get(0).getRules().get(0).getAnnotations())
                .doesNotContainKey("runbook_url");
    }

    /** 读详情时把 annotations.runbook_url 摘回平台侧字段。 */
    @Test
    void get_fills_the_runbook_url_back_from_annotations() {
        PrometheusRuleDTO stored = sample("my-app");
        stored.getGroups().get(0).getRules().get(0)
                .setAnnotations(new java.util.LinkedHashMap<>(Map.of("runbook_url", "https://runbooks/HighCPU")));
        when(k8s.get(any())).thenReturn(stored);

        PrometheusRuleDTO out = svc.get("app-rules", "t1", "c1", "my-app");

        assertThat(out.getGroups().get(0).getRules().get(0).getRunbookUrl())
                .isEqualTo("https://runbooks/HighCPU");
    }

    // ---------- S2.2.4 通知方式 ----------

    /** 保存时给每条 alert 规则代打 platform_notify；record 规则不打；平台侧字段不下发。 */
    @Test
    void create_stamps_the_notify_label_on_alert_rules_only() {
        PrometheusRuleDTO dto = sample("my-app");
        dto.setNotifyChannel("ops-mail");
        dto.setNotifyUserIds(List.of("u1"));
        PrometheusRuleDTO.Rule rec = new PrometheusRuleDTO.Rule();
        rec.setRecord("job:x:rate5m");
        rec.setExpr("vector(1)");
        rec.setLabels(new java.util.LinkedHashMap<>());   // 有容器但**不该**被加 platform_notify
        rec.setAnnotations(new java.util.LinkedHashMap<>());
        dto.getGroups().get(0).setRules(List.of(dto.getGroups().get(0).getRules().get(0), rec));
        when(k8s.create(any())).thenAnswer(i -> i.getArgument(0));

        svc.create(dto);

        PrometheusRuleDTO sent = captured();
        assertThat(sent.getGroups().get(0).getRules().get(0).getLabels())
                .containsEntry("platform_notify", "ops-mail");
        // CR 名走 annotation（label 值上限 63，长规则名会存不下）
        assertThat(sent.getGroups().get(0).getRules().get(0).getAnnotations())
                .containsEntry("platform_rule", "app-rules");
        assertThat(sent.getGroups().get(0).getRules().get(1).getLabels())
                .doesNotContainKey("platform_notify");
        assertThat(sent.getGroups().get(0).getRules().get(1).getAnnotations())
                .doesNotContainKey("platform_rule");
        // 平台侧字段不下发
        assertThat(sent.getNotifyChannel()).isNull();
        assertThat(sent.getNotifyUserIds()).isNull();
    }

    /** 没选渠道 → 不写 label（写空串会让告警带一个无意义的 platform_notify=""）。 */
    @Test
    void create_without_a_channel_writes_no_notify_label() {
        PrometheusRuleDTO dto = sample("my-app");
        when(k8s.create(any())).thenAnswer(i -> i.getArgument(0));

        svc.create(dto);

        assertThat(captured().getGroups().get(0).getRules().get(0).getLabels())
                .doesNotContainKey("platform_notify");
    }

    /** 绑定在打 label **之后**、K8s 写成功**之后**写（写失败就不该留绑定），且剥离平台侧字段。 */
    @Test
    void update_binds_after_the_write_and_strips_the_platform_fields() {
        PrometheusRuleDTO dto = sample("my-app");
        dto.setClusterId("c1");
        dto.setTenantId("t1");
        dto.setNotifyChannel("ops-mail");
        dto.setNotifyUserIds(List.of("u1"));
        when(k8s.update(any())).thenAnswer(i -> i.getArgument(0));

        svc.update(dto);

        verify(binder).bind("c1", "t1", "my-app", "app-rules", "ops-mail", List.of("u1"));
        PrometheusRuleDTO sent = capturedUpdate();
        assertThat(sent.getGroups().get(0).getRules().get(0).getLabels())
                .containsEntry("platform_notify", "ops-mail");
        assertThat(sent.getNotifyChannel()).isNull();
    }

    /** 参数不合法（渠道不存在 / 收件人越界）必须在**写 K8s 之前**被拒 —— 不留任何副作用。 */
    @Test
    void an_invalid_binding_is_rejected_before_the_write() {
        PrometheusRuleDTO dto = sample("my-app");
        dto.setClusterId("c1");
        dto.setTenantId("t1");
        dto.setNotifyChannel("nope");
        org.mockito.Mockito.doThrow(new CloudPlatformException(
                        com.coding.common.exception.EnumResponseType.NOTIFY_CHANNEL_INVALID, "通知方式不存在: nope"))
                .when(binder).validate(any(), any(), any());

        assertThatThrownBy(() -> svc.create(dto)).isInstanceOf(CloudPlatformException.class);

        verify(k8s, org.mockito.Mockito.never()).create(any());
        verify(binder, org.mockito.Mockito.never()).bind(any(), any(), any(), any(), any(), any());
    }

    private PrometheusRuleDTO captured() {
        ArgumentCaptor<PrometheusRuleDTO> c = ArgumentCaptor.forClass(PrometheusRuleDTO.class);
        verify(k8s).create(c.capture());
        return c.getValue();
    }

    private PrometheusRuleDTO capturedUpdate() {
        ArgumentCaptor<PrometheusRuleDTO> c = ArgumentCaptor.forClass(PrometheusRuleDTO.class);
        verify(k8s).update(c.capture());
        return c.getValue();
    }
}
