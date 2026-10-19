package com.coding.platformapi.notify;

import com.coding.data.models.alert.NotificationChannel;
import com.coding.data.models.k8s.PlatformAlertEvent;
import com.coding.platformapi.services.NotificationChannelService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailSenderTest {

    private EmailSender sender;

    @BeforeEach
    void setUp() {
        NotificationChannelService channels = mock(NotificationChannelService.class);
        when(channels.decryptedConfig(any())).thenReturn(Map.of(
                "host", "smtp.example.com", "port", "465",
                "from", "a@example.com", "username", "a@example.com", "password", "secret"));
        sender = spy(new EmailSender(channels));
    }

    private static NotificationChannel channel() {
        NotificationChannel c = new NotificationChannel();
        c.setCode("ops-mail");
        c.setName("运维邮件");
        c.setType("EMAIL");
        c.setConfig("{}");
        return c;
    }

    private static PlatformAlertEvent event(long id, String status, String alertname) {
        PlatformAlertEvent e = new PlatformAlertEvent();
        e.setId(id);
        e.setClusterId("31dddb93d5ba5d00");
        e.setAlertname(alertname);
        e.setNamespace("monitor");
        e.setSeverity("critical");
        e.setStatus(status);
        e.setStartsAt(new java.util.Date());
        e.setLabels("{\"alertname\":\"" + alertname + "\",\"namespace\":\"monitor\",\"pod\":\"p-0\"}");
        e.setAnnotations("{\"platform_rule\":\"test-rule\","
                + "\"summary\":\"All Alertmanager instances failed to send notifications.\","
                + "\"description\":\"Alertmanager failed to send 22.22% of notifications.\","
                + "\"runbook_url\":\"https://runbooks/x\"}");
        return e;
    }

    private static NotifyContext ctx() {
        return new NotifyContext("coding-cluster",
                Map.of(1L, "t1"), Map.of(1L, "平台默认租户"),
                Map.of(1L, "rate(node_cpu_seconds_total[5m]) < 0.2"));
    }

    // ---------- 主题 ----------

    /** 单条：主题里带告警名与严重度（收件箱里就能筛）。 */
    @Test
    void a_single_alert_subject_names_the_alert_and_its_severity() {
        assertThat(sender.subject(channel(), List.of(event(1, "firing", "HighCPU"))))
                .isEqualTo("[firing] HighCPU · critical · 运维邮件");
    }

    /** 多条报条数；混合状态分开写。 */
    @Test
    void a_mixed_batch_subject_counts_each_status() {
        assertThat(sender.subject(channel(), List.of(
                event(1, "firing", "HighCPU"), event(2, "resolved", "HighCPU"))))
                .isEqualTo("[告警] 2 条（1 firing / 1 resolved） · 运维邮件");
    }

    // ---------- 正文 ----------

    /**
     * 正文必须回答「谁 / 在哪 / 哪条规则 / 什么表达式 / 为什么 / 多严重」——
     * 这是这一版的主要诉求（原来只有一行 alertname | ns | severity | summary）。
     */
    @Test
    void the_body_describes_the_alert_completely() {
        String body = sender.body(List.of(event(1, "firing", "HighCPU")), ctx());

        assertThat(body)
                .contains("告警: HighCPU")
                .contains("集群: coding-cluster")
                .contains("租户: 平台默认租户")
                .contains("命名空间: monitor")
                .contains("规则: test-rule")
                .contains("严重度: critical")
                .contains("状态: firing")
                .contains("表达式: rate(node_cpu_seconds_total[5m]) < 0.2")
                .contains("摘要: All Alertmanager instances failed to send notifications.")
                .contains("详情: Alertmanager failed to send 22.22% of notifications.")
                .contains("Runbook: https://runbooks/x")
                .contains("标签: alertname=HighCPU, namespace=monitor, pod=p-0");
    }

    /** 规则没写 summary 时**明说**，而不是留一行空白让人以为是发送错了。 */
    @Test
    void a_rule_without_summary_says_so() {
        PlatformAlertEvent e = event(1, "firing", "test1");
        e.setAnnotations("{\"platform_rule\":\"test-rule\"}");

        String body = sender.body(List.of(e), ctx());

        assertThat(body).contains("摘要: —（这条规则没有写 summary 注解）");
        assertThat(body).contains("规则: test-rule");
    }

    /** 表达式查不到时也明说，而不是悄悄少一行。 */
    @Test
    void a_missing_expression_is_stated_explicitly() {
        String body = sender.body(List.of(event(1, "firing", "HighCPU")), NotifyContext.EMPTY);

        assertThat(body).contains("表达式: —（Prometheus 里没查到）");
        // 没有上下文时，集群回落成 clusterId、租户说明未分配
        assertThat(body).contains("集群: 31dddb93d5ba5d00");
        assertThat(body).contains("租户: —（命名空间未分配给任何租户）");
    }

    /** 一条告警一段，多条之间有分隔线（不能糊成一坨）。 */
    @Test
    void multiple_alerts_are_separated() {
        String body = sender.body(List.of(
                event(1, "firing", "HighCPU"), event(2, "firing", "DiskFull")), ctx());

        assertThat(body).contains("==== firing（2 条）====");
        assertThat(body).contains("----------------------------------------");
    }

    // ---------- 发送 ----------

    /** 主题与收件人来自这一批；真调用一次 send。 */
    @Test
    void the_message_is_sent_with_the_built_subject() throws Exception {
        JavaMailSenderImpl mail = mock(JavaMailSenderImpl.class);
        when(mail.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        doReturn(mail).when(sender).mailSender(any());

        sender.send(channel(), List.of("a@x.com", "b@x.com"),
                List.of(event(1, "firing", "HighCPU")), ctx());

        ArgumentCaptor<MimeMessage> cap = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail).send(cap.capture());
        assertThat(cap.getValue().getSubject()).isEqualTo("[firing] HighCPU · critical · 运维邮件");
        assertThat(cap.getValue().getAllRecipients()).hasSize(2);
    }

    /** 没有有效收件人 → 直接失败（不能发一封没人收的邮件）。 */
    @Test
    void an_empty_recipient_list_fails_loudly() {
        assertThatThrownBy(() -> sender.send(channel(), List.of(),
                List.of(event(1, "firing", "HighCPU")), ctx()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("收件人");
    }
}
