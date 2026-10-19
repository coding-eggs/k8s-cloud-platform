package com.coding.platformapi.services;

import com.coding.data.mapper.alert.AlertDeliveryMapper;
import com.coding.data.mapper.alert.RuleNotifyMapper;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.models.alert.AlertDelivery;
import com.coding.data.models.alert.NotificationChannel;
import com.coding.data.models.alert.RuleNotify;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.data.models.k8s.PlatformAlertEvent;
import com.coding.platformapi.notify.NotificationSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 投递决策是这个特性里唯一"错了不会报错、只会静默少发"的地方 —— 每条用例都盯一个决策点。
 */
class AlertDispatchServiceTest {

    private NotificationChannelService channels;
    private RuleNotifyMapper bindings;
    private RuleSourceResolver resolver;
    private AlertDeliveryMapper deliveries;
    private NotificationUserService users;
    private PlatformTenantNamespaceMapper allocs;
    private NotificationSender sender;
    private AlertDispatchService svc;

    @BeforeEach
    void setUp() throws Exception {
        channels = mock(NotificationChannelService.class);
        bindings = mock(RuleNotifyMapper.class);
        resolver = mock(RuleSourceResolver.class);
        deliveries = mock(AlertDeliveryMapper.class);
        users = mock(NotificationUserService.class);
        allocs = mock(PlatformTenantNamespaceMapper.class);
        sender = mock(NotificationSender.class);
        when(sender.type()).thenReturn("EMAIL");
        svc = spy(new AlertDispatchService(channels, bindings, resolver, deliveries, users, allocs,
                List.of(sender)));
        doNothing().when(svc).sleep(anyLong());          // 重试退避在测试里不真等
        when(deliveries.insertIfAbsent(any())).thenReturn(1);
    }

    // ---------- 夹具 ----------

    private static PlatformAlertEvent event(long id, String ns, String labels, String status) {
        PlatformAlertEvent e = new PlatformAlertEvent();
        e.setId(id);
        e.setClusterId("c1");
        e.setNamespace(ns);
        e.setAlertname("HighCPU");
        e.setStatus(status);
        e.setLabels(labels);
        return e;
    }

    private static NotificationChannel channel(String code, String tenantId, boolean enabled) {
        NotificationChannel c = new NotificationChannel();
        c.setCode(code);
        c.setName(code);
        c.setType("EMAIL");
        c.setTenantId(tenantId);
        c.setEnabled(enabled);
        return c;
    }

    private void tenantOwns(String ns, String tenantId) {
        PlatformTenantNamespace a = new PlatformTenantNamespace();
        a.setTenantId(tenantId);
        a.setNamespace(ns);
        when(allocs.selectByClusterAndNs("c1", ns)).thenReturn(List.of(a));
    }

    private void bindingFrom(String ruleName, List<String> userIds) {
        when(resolver.crNameOf(eq("c1"), any(), any())).thenReturn(ruleName);
        RuleNotify r = new RuleNotify();
        r.setChannelCode("ops-mail");
        r.setUserIds(userIds.stream().map(u -> "\"" + u + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]")));
        when(bindings.selectByRule(eq("c1"), any(), eq(ruleName))).thenReturn(r);
        when(users.emailsOf(any())).thenReturn(List.of("ops@x.com"));
    }

    // ---------- 渠道决策 ----------

    /** label 里的渠道优先（规则自己选的那条）。 */
    @Test
    void the_channel_comes_from_the_label_when_present() throws Exception {
        tenantOwns("ns", "t1");
        when(channels.resolve("ops-mail")).thenReturn(channel("ops-mail", null, true));
        bindingFrom("app-rules", List.of("u1"));

        svc.dispatch("c1", List.of(event(1, "ns", "{\"platform_notify\":\"ops-mail\"}", "firing")));

        verify(channels, org.mockito.Mockito.atLeastOnce()).resolve("ops-mail");
        verify(sender).send(any(), eq(List.of("ops@x.com")), any());
    }

    /** 没有 label → 默认渠道（租户默认优先，其次全局 —— 由 service.defaultFor 决定）。 */
    @Test
    void without_a_label_the_default_channel_is_used() throws Exception {
        tenantOwns("ns", "t1");
        when(channels.defaultFor("t1")).thenReturn(channel("default-mail", null, true));
        when(channels.resolve("default-mail")).thenReturn(channel("default-mail", null, true));
        bindingFrom("app-rules", List.of("u1"));

        svc.dispatch("c1", List.of(event(1, "ns", "{}", "firing")));

        verify(sender).send(any(), any(), any());
    }

    /** label 里的 code 在库里不存在（渠道被删了）→ 回落默认，而不是不发。 */
    @Test
    void an_unknown_channel_falls_back_to_the_default() throws Exception {
        tenantOwns("ns", "t1");
        when(channels.resolve("gone-mail")).thenReturn(null);
        when(channels.defaultFor("t1")).thenReturn(channel("default-mail", null, true));
        when(channels.resolve("default-mail")).thenReturn(channel("default-mail", null, true));
        bindingFrom("app-rules", List.of("u1"));

        svc.dispatch("c1", List.of(event(1, "ns", "{\"platform_notify\":\"gone-mail\"}", "firing")));

        verify(sender).send(any(), any(), any());
    }

    /** 渠道**停用**只影响"新选择"—— 已绑定的规则照常发（否则停用一次就静默断掉线上告警）。 */
    @Test
    void a_disabled_channel_still_sends() throws Exception {
        tenantOwns("ns", "t1");
        when(channels.resolve("ops-mail")).thenReturn(channel("ops-mail", null, false));
        bindingFrom("app-rules", List.of("u1"));

        svc.dispatch("c1", List.of(event(1, "ns", "{\"platform_notify\":\"ops-mail\"}", "firing")));

        verify(sender).send(any(), any(), any());
    }

    /** 规则租户 ≠ 渠道租户（且渠道非全局）→ 回落默认，不跨租户发。 */
    @Test
    void a_channel_of_another_tenant_falls_back_to_the_default() throws Exception {
        tenantOwns("ns", "t1");
        when(channels.resolve("other-tenant-mail")).thenReturn(channel("other-tenant-mail", "t2", true));
        when(channels.defaultFor("t1")).thenReturn(channel("default-mail", null, true));
        when(channels.resolve("default-mail")).thenReturn(channel("default-mail", null, true));
        bindingFrom("app-rules", List.of("u1"));

        svc.dispatch("c1", List.of(event(1, "ns", "{\"platform_notify\":\"other-tenant-mail\"}", "firing")));

        ArgumentCaptor<NotificationChannel> cap = ArgumentCaptor.forClass(NotificationChannel.class);
        verify(sender).send(cap.capture(), any(), any());
        assertThat(cap.getValue().getCode()).isEqualTo("default-mail");
    }

    // ---------- 收件人 ----------

    /** 绑定表命中 → 收件人来自绑定的用户（再过滤掉没邮箱的）。 */
    @Test
    void recipients_come_from_the_binding_when_it_exists() throws Exception {
        tenantOwns("ns", "t1");
        when(channels.resolve("ops-mail")).thenReturn(channel("ops-mail", null, true));
        bindingFrom("app-rules", List.of("u1", "u2"));

        svc.dispatch("c1", List.of(event(1, "ns", "{\"platform_notify\":\"ops-mail\"}", "firing")));

        verify(users).emailsOf(org.mockito.ArgumentMatchers.argThat(
                ids -> ids.contains("u1") && ids.contains("u2")));
        verify(users, never()).tenantAdminUserIds(anyString());
    }

    /**
     * 绑定按 {@code platform_rule} 标签找 —— 投递跑在 @Async 线程上，**没有 Authorization**，
     * 去集群反查只会拿到 401（2026-10-20 真跑验到）。标签缺了才回落到反查。
     */
    @Test
    void the_binding_is_found_from_the_platform_rule_annotation() throws Exception {
        tenantOwns("ns", "t1");
        when(channels.resolve("ops-mail")).thenReturn(channel("ops-mail", null, true));
        RuleNotify r = new RuleNotify();
        r.setChannelCode("ops-mail");
        r.setUserIds("[\"u9\"]");
        when(bindings.selectByRule("c1", "ns", "app-rules")).thenReturn(r);
        when(users.emailsOf(any())).thenReturn(List.of("u9@x.com"));

        PlatformAlertEvent e = event(1, "ns", "{\"platform_notify\":\"ops-mail\"}", "firing");
        e.setAnnotations("{\"platform_rule\":\"app-rules\"}");
        svc.dispatch("c1", List.of(e));

        verify(resolver, never()).crNameOf(any(), any(), any());
        verify(sender).send(any(), eq(List.of("u9@x.com")), any());
    }

    /** 规则改名/删除后绑定查不到 → 回落到该命名空间所属租户的管理员。 */
    @Test
    void recipients_fall_back_to_the_tenant_admin_when_the_binding_is_gone() throws Exception {        tenantOwns("ns", "t1");
        when(channels.resolve("ops-mail")).thenReturn(channel("ops-mail", null, true));
        when(resolver.crNameOf(eq("c1"), any(), any())).thenReturn(null);
        when(users.tenantAdminUserIds("t1")).thenReturn(List.of("admin1"));
        when(users.emailsOf(any())).thenReturn(List.of("admin@x.com"));

        svc.dispatch("c1", List.of(event(1, "ns", "{\"platform_notify\":\"ops-mail\"}", "firing")));

        verify(users).tenantAdminUserIds("t1");
        verify(sender).send(any(), eq(List.of("admin@x.com")), any());
    }

    /** 一个有效收件人都没有 → 记 FAILED，**不调用发送器**（否则会"给空列表发一封"）。 */
    @Test
    void no_recipients_marks_the_delivery_failed_and_sends_nothing() throws Exception {
        tenantOwns("ns", "t1");
        when(channels.resolve("ops-mail")).thenReturn(channel("ops-mail", null, true));
        bindingFrom("app-rules", List.of("u1"));
        when(users.emailsOf(any())).thenReturn(List.of());

        svc.dispatch("c1", List.of(event(1, "ns", "{\"platform_notify\":\"ops-mail\"}", "firing")));

        verify(sender, never()).send(any(), any(), any());
        verify(deliveries).markFailed(any(), eq("无有效收件人"), eq(0));
    }

    /** 连渠道都没有（没配默认）→ 也留一条 FAILED 的记录，而不是什么都没有。 */
    @Test
    void no_channel_at_all_is_recorded_as_failed() throws Exception {
        tenantOwns("ns", "t1");
        when(channels.defaultFor("t1")).thenReturn(null);

        svc.dispatch("c1", List.of(event(1, "ns", "{}", "firing")));

        verify(deliveries).markFailed(any(), eq("未配置通知渠道"), eq(0));
        verify(sender, never()).send(any(), any(), any());
    }

    // ---------- 去重 / 合并 / 重试 ----------

    /** 已经投递过（唯一键命中、insert 返回 0）→ 不再发。这条守的是 Alertmanager 重推。 */
    @Test
    void a_second_dispatch_of_the_same_alert_sends_nothing() throws Exception {
        tenantOwns("ns", "t1");
        when(channels.resolve("ops-mail")).thenReturn(channel("ops-mail", null, true));
        bindingFrom("app-rules", List.of("u1"));
        when(deliveries.insertIfAbsent(any())).thenReturn(0);      // ← 已有记录

        svc.dispatch("c1", List.of(event(1, "ns", "{\"platform_notify\":\"ops-mail\"}", "firing")));

        verify(sender, never()).send(any(), any(), any());
        verify(deliveries, never()).markSent(any());
    }

    /** 一次 dispatch 里两条走同一渠道 → **合并成一封**。 */
    @Test
    void events_on_the_same_channel_are_sent_in_one_batch() throws Exception {
        tenantOwns("ns", "t1");
        when(channels.resolve("ops-mail")).thenReturn(channel("ops-mail", null, true));
        bindingFrom("app-rules", List.of("u1"));

        svc.dispatch("c1", List.of(
                event(1, "ns", "{\"platform_notify\":\"ops-mail\"}", "firing"),
                event(2, "ns", "{\"platform_notify\":\"ops-mail\"}", "firing")));

        verify(sender, times(1)).send(any(), any(), any());
        verify(deliveries, times(2)).markSent(any());
    }

    /** 发送失败 → 重试 3 次后记 FAILED，attempts=3。 */
    @Test
    void a_failing_sender_is_retried_three_times_then_marked_failed() throws Exception {
        tenantOwns("ns", "t1");
        when(channels.resolve("ops-mail")).thenReturn(channel("ops-mail", null, true));
        bindingFrom("app-rules", List.of("u1"));
        doThrow(new RuntimeException("smtp down")).when(sender).send(any(), any(), any());

        svc.dispatch("c1", List.of(event(1, "ns", "{\"platform_notify\":\"ops-mail\"}", "firing")));

        verify(sender, times(3)).send(any(), any(), any());
        verify(deliveries).markFailed(any(), eq("smtp down"), eq(3));
        verify(deliveries, never()).markSent(any());
    }

    /** 发送成功 → 记 SENT。 */
    @Test
    void a_successful_send_marks_the_delivery_sent() throws Exception {
        tenantOwns("ns", "t1");
        when(channels.resolve("ops-mail")).thenReturn(channel("ops-mail", null, true));
        bindingFrom("app-rules", List.of("u1"));

        svc.dispatch("c1", List.of(event(1, "ns", "{\"platform_notify\":\"ops-mail\"}", "firing")));

        ArgumentCaptor<AlertDelivery> cap = ArgumentCaptor.forClass(AlertDelivery.class);
        verify(deliveries).insertIfAbsent(cap.capture());
        assertThat(cap.getValue().getAlertStatus()).isEqualTo("firing");
        verify(deliveries).markSent(any());
    }

    /**
     * ⚠️ Alertmanager 的 groupBy 是 (namespace, alertname)：一个已在 firing 的组里新加入一条告警时，
     * 它会把**整组**重推过来。邮件里只能出现本次真正领取成功的那条，
     * 否则用户会反复收到把老告警又列一遍的邮件（评审 Critical #1）。
     */
    @SuppressWarnings("unchecked")
    @Test
    void only_newly_claimed_events_go_into_the_email() throws Exception {
        tenantOwns("ns", "t1");
        when(channels.resolve("ops-mail")).thenReturn(channel("ops-mail", null, true));
        bindingFrom("app-rules", List.of("u1"));
        // 第一条领取成功，第二条已有投递记录
        when(deliveries.insertIfAbsent(any())).thenReturn(1, 0);

        PlatformAlertEvent e1 = event(1, "ns", "{\"platform_notify\":\"ops-mail\"}", "firing");
        e1.setAnnotations("{\"platform_rule\":\"app-rules\"}");
        PlatformAlertEvent e2 = event(2, "ns", "{\"platform_notify\":\"ops-mail\"}", "firing");
        e2.setAnnotations("{\"platform_rule\":\"app-rules\"}");
        svc.dispatch("c1", List.of(e1, e2));

        ArgumentCaptor<List<PlatformAlertEvent>> cap = ArgumentCaptor.forClass(List.class);
        verify(sender).send(any(), any(), cap.capture());
        assertThat(cap.getValue()).hasSize(1);
        assertThat(cap.getValue().get(0).getId()).isEqualTo(1L);
    }

    /** 没有 id 的告警（id 回查失败）→ 跳过：投递记录按 event_id 去重，null 写不进去。 */
    @Test
    void events_without_an_id_are_skipped() throws Exception {
        tenantOwns("ns", "t1");
        when(channels.resolve("ops-mail")).thenReturn(channel("ops-mail", null, true));
        bindingFrom("app-rules", List.of("u1"));

        PlatformAlertEvent noId = event(1, "ns", "{\"platform_notify\":\"ops-mail\"}", "firing");
        noId.setId(null);

        svc.dispatch("c1", List.of(noId));

        verify(deliveries, never()).insertIfAbsent(any());
        verify(sender, never()).send(any(), any(), any());
    }
}
