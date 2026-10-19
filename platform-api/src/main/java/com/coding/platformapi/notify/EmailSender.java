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
 * <p>正文用**纯文本**，但每条告警都写全：谁（租户）、在哪（集群 / 命名空间）、哪条规则、
 * 什么表达式、为什么（summary / description）、多严重、从什么时候开始的。
 * Alertmanager 的 payload 里没有租户名/集群名/表达式，这几项由 {@link NotifyContext} 补进来。
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
    public void send(NotificationChannel channel, List<String> recipients,
                     List<PlatformAlertEvent> events, NotifyContext context) throws Exception {
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
        helper.setText(body(events, context), false);
        mail.send(helper.getMimeMessage());
    }

    /** 主题：单条把告警名与严重度带上（收件箱里就能筛），多条报条数。 */
    String subject(NotificationChannel channel, List<PlatformAlertEvent> events) {
        long firing = count(events, "firing");
        long resolved = count(events, "resolved");
        String head;
        if (events.size() == 1) {
            PlatformAlertEvent only = events.get(0);
            head = "[" + only.getStatus() + "] " + nvl(only.getAlertname())
                    + (StringUtils.hasText(only.getSeverity()) ? " · " + only.getSeverity() : "");
        } else if (firing > 0 && resolved > 0) {
            head = "[告警] " + events.size() + " 条（" + firing + " firing / " + resolved + " resolved）";
        } else if (resolved > 0) {
            head = "[resolved] " + resolved + " 条告警";
        } else {
            head = "[firing] " + firing + " 条告警";
        }
        return channel.getName() == null ? head : head + " · " + channel.getName();
    }

    /** 正文：每条告警一段完整描述（不是一行摘要）。 */
    String body(List<PlatformAlertEvent> events, NotifyContext context) {
        StringBuilder sb = new StringBuilder();
        appendBlock(sb, events, "firing", context);
        appendBlock(sb, events, "resolved", context);
        return sb.toString();
    }

    private void appendBlock(StringBuilder sb, List<PlatformAlertEvent> events,
                             String status, NotifyContext context) {
        List<PlatformAlertEvent> block = events.stream().filter(e -> status.equals(e.getStatus())).toList();
        if (block.isEmpty()) return;
        sb.append("==== ").append(status).append("（").append(block.size()).append(" 条）====\n\n");
        for (int i = 0; i < block.size(); i++) {
            if (i > 0) {
                sb.append("----------------------------------------\n");
            }
            appendAlert(sb, block.get(i), context);
            sb.append('\n');
        }
    }

    private void appendAlert(StringBuilder sb, PlatformAlertEvent e, NotifyContext context) {
        boolean firing = !"resolved".equals(e.getStatus());
        Map<String, String> annotations = parseMap(e.getAnnotations());
        Map<String, String> labels = parseMap(e.getLabels());

        String tenantName = context == null ? null : context.tenantName(e.getId());
        String clusterName = context == null ? null : context.clusterName();
        String expression = context == null ? null : context.expression(e.getId());
        String rule = annotations.get("platform_rule");
        String summary = annotations.get("summary");
        String description = annotations.get("description");
        String runbook = annotations.get("runbook_url");

        field(sb, "告警", e.getAlertname());
        field(sb, "集群", StringUtils.hasText(clusterName) ? clusterName : e.getClusterId());
        field(sb, "租户", StringUtils.hasText(tenantName) ? tenantName : "—（命名空间未分配给任何租户）");
        field(sb, "命名空间", nvl(e.getNamespace()));
        field(sb, "规则", StringUtils.hasText(rule) ? rule : "—（不是经平台保存的规则）");
        field(sb, "严重度", nvl(e.getSeverity()));
        field(sb, "状态", e.getStatus());
        field(sb, "开始", time(e.getStartsAt()) + durationHint(e, firing));
        if (firing) {
            field(sb, "表达式", StringUtils.hasText(expression) ? expression : "—（Prometheus 里没查到）");
            // 注：{{ $labels.xxx }} 这类模板**是 Prometheus 求值时展开的**，
            // 告警推到平台时已经是渲染好的文本，这里原样打印即可。
            field(sb, "摘要", StringUtils.hasText(summary) ? summary : "—（这条规则没有写 summary 注解）");
        } else {
            field(sb, "结束", time(e.getEndsAt()));
            field(sb, "表达式", StringUtils.hasText(expression) ? expression : "—（Prometheus 里没查到）");
            field(sb, "摘要", StringUtils.hasText(summary) ? summary : "—（这条规则没有写 summary 注解）");
        }
        field(sb, "详情", description);
        field(sb, "Runbook", runbook);
        if (!labels.isEmpty()) {
            field(sb, "标签", joinLabels(labels));
        }
    }

    /** 一行一个字段；值为空的行直接不写（脏输出比少一行更难读）。 */
    private static void field(StringBuilder sb, String label, String value) {
        if (!StringUtils.hasText(value)) return;
        sb.append(label).append(": ").append(value).append('\n');
    }

    private static String durationHint(PlatformAlertEvent e, boolean firing) {
        if (!firing) {
            return "";
        }
        if (e.getStartsAt() == null) {
            return "";
        }
        long ms = System.currentTimeMillis() - e.getStartsAt().getTime();
        return ms <= 0 ? "" : "（已持续 " + humanDuration(ms) + "）";
    }

    private static String humanDuration(long ms) {
        long sec = ms / 1000;
        if (sec < 60) return sec + " 秒";
        long min = sec / 60;
        if (min < 60) return min + " 分钟";
        long hour = min / 60;
        if (hour < 24) return hour + " 小时 " + (min % 60) + " 分";
        return (hour / 24) + " 天 " + (hour % 24) + " 小时";
    }

    private static String joinLabels(Map<String, String> labels) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> en : labels.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(en.getKey()).append('=').append(en.getValue());
        }
        return sb.toString();
    }

    private String time(Date d) {
        return d == null ? "—" : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }

    private static String nvl(String s) {
        return StringUtils.hasText(s) ? s : "—";
    }

    private static long count(List<PlatformAlertEvent> events, String status) {
        return events.stream().filter(e -> status.equals(e.getStatus())).count();
    }

    /** labels / annotations 是 JSON 串；坏值当空 map（正文少几行，不能因此发不出去）。 */
    private Map<String, String> parseMap(String jsonText) {
        if (!StringUtils.hasText(jsonText)) return Map.of();
        try {
            Map<String, String> m = json.readValue(jsonText,
                    new TypeReference<LinkedHashMap<String, String>>() {});
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            return Map.of();
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
        // ⚠️ 必须给超时：SMTP 配错（端口被防火墙丢包）时 JavaMail 默认**无限等**，
        // 4 个投递线程各卡一次 + 3 次重试就把池子永久占满，后面的告警全堵在队列里。
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "30000");
        props.put("mail.smtp.writetimeout", "30000");
        impl.setJavaMailProperties(props);
        return impl;
    }
}
