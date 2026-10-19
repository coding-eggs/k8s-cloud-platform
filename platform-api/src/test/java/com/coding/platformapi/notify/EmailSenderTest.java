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

    private NotificationChannelService channels;
    private EmailSender sender;

    @BeforeEach
    void setUp() {
        channels = mock(NotificationChannelService.class);
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

    private static PlatformAlertEvent event(String status, String alertname) {
        PlatformAlertEvent e = new PlatformAlertEvent();
        e.setAlertname(alertname);
        e.setNamespace("mon-app");
        e.setSeverity("critical");
        e.setStatus(status);
        e.setStartsAt(new java.util.Date());
        return e;
    }

    /** 主题里带状态与条数；收件人就是传进来的那一批。 */
    @Test
    void the_message_subject_and_recipients_are_built_from_the_batch() throws Exception {
        JavaMailSenderImpl mail = mock(JavaMailSenderImpl.class);
        when(mail.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        doReturn(mail).when(sender).mailSender(any());

        sender.send(channel(), List.of("a@x.com", "b@x.com"),
                List.of(event("firing", "HighCPU"), event("firing", "DiskFull")));

        ArgumentCaptor<MimeMessage> cap = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail).send(cap.capture());
        assertThat(cap.getValue().getSubject()).isEqualTo("[firing] 2 条告警 · 运维邮件");
        assertThat(cap.getValue().getAllRecipients()).hasSize(2);
    }

    /** 混合状态：主题写清 firing / resolved 各几条。 */
    @Test
    void a_mixed_batch_says_how_many_of_each() throws Exception {
        JavaMailSenderImpl mail = mock(JavaMailSenderImpl.class);
        when(mail.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        doReturn(mail).when(sender).mailSender(any());

        sender.send(channel(), List.of("a@x.com"),
                List.of(event("firing", "HighCPU"), event("resolved", "HighCPU")));

        ArgumentCaptor<MimeMessage> cap = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail).send(cap.capture());
        assertThat(cap.getValue().getSubject()).isEqualTo("[告警] 2 条（1 firing / 1 resolved） · 运维邮件");
    }

    /** 没有有效收件人 → 直接失败（不能发一封没人收的邮件）。 */
    @Test
    void an_empty_recipient_list_fails_loudly() {
        assertThatThrownBy(() -> sender.send(channel(), List.of(), List.of(event("firing", "HighCPU"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("收件人");
    }
}
