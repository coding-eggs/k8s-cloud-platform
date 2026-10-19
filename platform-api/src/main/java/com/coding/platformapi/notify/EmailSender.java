package com.coding.platformapi.notify;

import com.coding.data.models.alert.NotificationChannel;
import com.coding.data.models.k8s.PlatformAlertEvent;
import com.coding.platformapi.services.NotificationChannelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 邮件发送器。**每个渠道自带 SMTP 配置**（{@code config} JSON），刻意不读全局 {@code spring.mail.*}
 * —— 一个平台可以有多条邮件渠道（运维 / 值班 / 各租户自己的一条）。
 *
 * <p>正文用**纯文本**：模板可自定义是后续的事，这里只为「看得懂、能定位」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailSender implements NotificationSender {

    private final NotificationChannelService channels;
    private final JsonMapper json = JsonMapper.builder().build();

    @Override
    public String type() {
        return "EMAIL";
    }

    @Override
    public void send(NotificationChannel channel, List<String> recipients, List<PlatformAlertEvent> events)
            throws Exception {
        if (recipients == null || recipients.isEmpty()) {
            // 不能"发一封没人收的邮件"然后记成功 —— 那会让「配置漏了收件人」永远看不见
            throw new IllegalStateException("没有有效收件人");
        }
        JavaMailSenderImpl mail = mailSender(channel);
        Map<String, String> cfg = channels.decryptedConfig(channel);

        MimeMessageHelper helper = new MimeMessageHelper(mail.createMimeMessage(), false, "UTF-8");
        String from = StringUtils.hasText(cfg.get("from")) ? cfg.get("from") : cfg.get("username");
        if (StringUtils.hasText(from)) {
            helper.setFrom(from);
        }
        helper.setTo(recipients.toArray(new String[0]));
        helper.setSubject(subject(channel, events));
        helper.setText(body(events), false);
        mail.send(helper.getMimeMessage());
    }

    /** 主题：单一状态直说条数，混合时分开写。 */
    String subject(NotificationChannel channel, List<PlatformAlertEvent> events) {
        long firing = count(events, "firing");
        long resolved = count(events, "resolved");
        String head = firing > 0 && resolved > 0
                ? "[告警] " + events.size() + " 条（" + firing + " firing / " + resolved + " resolved）"
                : (resolved > 0 ? "[resolved] " + resolved + " 条告警" : "[firing] " + firing + " 条告警");
        return channel.getName() == null ? head : head + " · " + channel.getName();
    }

    /** 正文：firing / resolved 分块，每条一行主信息 + 一行时间。 */
    String body(List<PlatformAlertEvent> events) {
        StringBuilder sb = new StringBuilder();
        appendBlock(sb, events, "firing", true);
        appendBlock(sb, events, "resolved", false);
        return sb.toString();
    }

    private void appendBlock(StringBuilder sb, List<PlatformAlertEvent> events,
                             String status, boolean firing) {
        List<PlatformAlertEvent> block = events.stream().filter(e -> status.equals(e.getStatus())).toList();
        if (block.isEmpty()) return;
        sb.append("==== ").append(status).append("（").append(block.size()).append(" 条）====\n");
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        for (PlatformAlertEvent e : block) {
            sb.append("- ").append(e.getAlertname())
              .append(" | ").append(e.getNamespace() == null ? "-" : e.getNamespace())
              .append(" | ").append(e.getSeverity() == null ? "-" : e.getSeverity())
              .append(" | ").append(summary(e.getAnnotations()))
              .append('\n');
            Date at = firing ? e.getStartsAt() : e.getEndsAt();
            if (at != null) {
                sb.append("  开始: ").append(fmt.format(at)).append('\n');
            }
        }
        sb.append('\n');
    }

    private static long count(List<PlatformAlertEvent> events, String status) {
        return events.stream().filter(e -> status.equals(e.getStatus())).count();
    }

    /** annotations 是 JSON 串；没有 summary 就留空（不猜）。 */
    private String summary(String annotationsJson) {
        if (!StringUtils.hasText(annotationsJson)) return "";
        try {
            Map<String, String> m = json.readValue(annotationsJson,
                    new TypeReference<LinkedHashMap<String, String>>() {});
            return m == null ? "" : String.valueOf(m.getOrDefault("summary", ""));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 按渠道配置造一个邮件发送器（**每渠道一份**，不共享全局配置）。
     * <p>包级可见：测试替换它而不真连 SMTP。
     */
    JavaMailSenderImpl mailSender(NotificationChannel channel) {
        Map<String, String> cfg = channels.decryptedConfig(channel);
        JavaMailSenderImpl impl = new JavaMailSenderImpl();
        impl.setHost(cfg.get("host"));
        int port = 25;
        try {
            if (StringUtils.hasText(cfg.get("port"))) port = Integer.parseInt(cfg.get("port").trim());
        } catch (NumberFormatException e) {
            log.warn("通知方式 {} 的端口不是数字，按 25 处理", channel.getCode());
        }
        impl.setPort(port);
        // 只有用户名 + 密码**都**给了才开认证：只填用户名（编辑时密码留空、又没沿用旧密文）
        // 会拿空密码去 AUTH，换回来一个让人摸不着头脑的 "Authentication failed"。
        boolean auth = StringUtils.hasText(cfg.get("username")) && StringUtils.hasText(cfg.get("password"));
        if (auth) {
            impl.setUsername(cfg.get("username"));
            impl.setPassword(cfg.get("password"));
        }
        // 465 默认隐式 TLS；其它端口默认 STARTTLS。config 里显式给了 ssl 就以它为准。
        boolean ssl = StringUtils.hasText(cfg.get("ssl"))
                ? Boolean.parseBoolean(cfg.get("ssl"))
                : port == 465;
        Properties props = new Properties();
        props.put("mail.smtp.auth", String.valueOf(auth));
        props.put("mail.smtp.ssl.enable", String.valueOf(ssl));
        props.put("mail.smtp.starttls.enable", String.valueOf(!ssl));
        impl.setJavaMailProperties(props);
        return impl;
    }
}
