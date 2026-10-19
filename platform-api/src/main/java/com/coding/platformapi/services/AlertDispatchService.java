package com.coding.platformapi.services;

import com.coding.data.mapper.alert.AlertDeliveryMapper;
import com.coding.data.mapper.alert.RuleNotifyMapper;
import com.coding.data.models.alert.AlertDelivery;
import com.coding.data.models.alert.NotificationChannel;
import com.coding.data.models.alert.RuleNotify;
import com.coding.data.models.k8s.PlatformAlertEvent;
import com.coding.platformapi.notify.AlertNotifyContextService;
import com.coding.platformapi.notify.NotificationSender;
import com.coding.platformapi.notify.NotifyContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 告警投递引擎：告警落库后决定「用哪个渠道、发给谁、发没发出去」。
 *
 * <p>三个不变量，每个都对应一类「错了不会报错、只会静默少发」的事故：
 * <ol>
 *   <li><b>幂等</b>：{@code platform_alert_delivery} 的唯一键是去重键。Alertmanager 的
 *       {@code repeat_interval} 会重推同一条告警，第二次必须什么都不发。</li>
 *   <li><b>不跨租户</b>：规则租户 ≠ 渠道租户（且渠道非全局）时回落默认渠道，绝不把 A 租户的
 *       告警发到 B 租户配置的收件人那里。</li>
 *   <li><b>不静默</b>：没有渠道 / 没有收件人 / 发送失败都落一条 FAILED 记录，
 *       失败原因进 {@code last_error} —— 「以为发了」比「没发」更难查。</li>
 * </ol>
 *
 * <p>投递失败**绝不影响** webhook 的 200：它跑在 {@code alertDispatchExecutor} 上。
 */
@Slf4j
@Service
public class AlertDispatchService {

    private static final int MAX_ATTEMPTS = 3;
    private static final long[] BACKOFF_MS = {1_000, 5_000, 30_000};
    private static final int MAX_ERROR_LEN = 1000;

    private final NotificationChannelService channels;
    private final RuleNotifyMapper bindings;
    private final RuleSourceResolver ruleSourceResolver;
    private final AlertDeliveryMapper deliveries;
    private final NotificationUserService userService;
    /** 集群名 / 租户 / 表达式 —— 告警 payload 里没有，通知正文与归属都要 */
    private final AlertNotifyContextService notifyContext;
    private final Map<String, NotificationSender> senders;
    private final JsonMapper json = JsonMapper.builder().build();

    public AlertDispatchService(NotificationChannelService channels,
                                RuleNotifyMapper bindings,
                                RuleSourceResolver ruleSourceResolver,
                                AlertDeliveryMapper deliveries,
                                NotificationUserService userService,
                                AlertNotifyContextService notifyContext,
                                List<NotificationSender> senders) {
        this.channels = channels;
        this.bindings = bindings;
        this.ruleSourceResolver = ruleSourceResolver;
        this.deliveries = deliveries;
        this.userService = userService;
        this.notifyContext = notifyContext;
        Map<String, NotificationSender> byType = new HashMap<>();
        for (NotificationSender s : senders) {
            byType.put(s.type(), s);
        }
        this.senders = Map.copyOf(byType);
    }

    /** 发送前钩子：本轮原样返回。**告警压制挂在这里**（下一轮）。 */
    protected List<PlatformAlertEvent> beforeSend(List<PlatformAlertEvent> events) {
        return events;
    }

    @Async("alertDispatchExecutor")
    public void dispatch(String clusterId, List<PlatformAlertEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        List<PlatformAlertEvent> toSend = beforeSend(events);
        if (toSend == null || toSend.isEmpty()) {
            return;
        }
        // 没有 id 的行没法记投递（唯一键要用 event_id）—— 宁可少发一封，也不要写坏一行
        toSend = toSend.stream().filter(e -> e != null && e.getId() != null).toList();
        if (toSend.isEmpty()) {
            log.warn("这一批告警都没有 id，跳过投递");
            return;
        }

        // 0) 归属与表达式：一次算好，渠道决策、收件人、正文三处共用
        NotifyContext context = notifyContext.build(clusterId, toSend);

        // 1) 决定每条告警走哪个渠道
        Map<String, List<PlatformAlertEvent>> byChannel = new LinkedHashMap<>();
        for (PlatformAlertEvent e : toSend) {
            String tenantId = context.tenantId(e.getId());
            NotificationChannel c = channels.resolve(mapValue(e.getLabels(), "platform_notify"));
            // 跨租户：规则租户与渠道租户不一致（且渠道非全局）→ 不允许，回落默认
            if (c != null && StringUtils.hasText(c.getTenantId()) && !c.getTenantId().equals(tenantId)) {
                log.warn("规则绑定了其他租户的通知方式，回落默认（code={} tenant={}）", c.getCode(), tenantId);
                c = null;
            }
            if (c == null) {
                c = channels.defaultFor(tenantId);
            }
            if (c == null) {
                markFail(e, "", "未配置通知渠道");
                continue;
            }
            byChannel.computeIfAbsent(c.getCode(), k -> new ArrayList<>()).add(e);
        }

        // 2) 按渠道合并发送
        for (Map.Entry<String, List<PlatformAlertEvent>> entry : byChannel.entrySet()) {
            NotificationChannel channel = channels.resolve(entry.getKey());
            List<PlatformAlertEvent> batch = entry.getValue();
            if (channel == null) {                       // 理论上不会：上面刚解析过
                batch.forEach(e -> markFail(e, entry.getKey(), "通知方式不存在: " + entry.getKey()));
                continue;
            }

            // 2.1 幂等领取：只有第一次才发
            List<AlertDelivery> claimed = new ArrayList<>();
            for (PlatformAlertEvent e : batch) {
                AlertDelivery d = new AlertDelivery();
                d.setEventId(e.getId());
                d.setChannelCode(channel.getCode());
                d.setAlertStatus(e.getStatus());
                d.setStatus("PENDING");
                d.setCreatedAt(new Date());
                if (deliveries.insertIfAbsent(d) == 1) {
                    claimed.add(d);
                }
            }
            if (claimed.isEmpty()) {
                continue;
            }

            // ⚠️ 投递只带**本次真正领取成功**的那几条。
            // Alertmanager 的 groupBy 是 (namespace, alertname)：一个已 firing 的组里新加入一条告警时，
            // 它会把**整组**重推过来 —— 用整个 batch 发信，用户会再收到一封把老告警又列一遍的邮件，
            // 而且 recipients 是对整个 batch 取并集，会把 A 规则的告警发给 B 规则的收件人。
            Set<Long> claimedIds = new HashSet<>();
            for (AlertDelivery d : claimed) {
                claimedIds.add(d.getEventId());
            }
            List<PlatformAlertEvent> mailEvents =
                    batch.stream().filter(ev -> claimedIds.contains(ev.getId())).toList();

            // 2.2 收件人
            List<String> recipients = recipientsFor(clusterId, mailEvents, context);
            if (recipients.isEmpty()) {
                claimed.forEach(d -> deliveries.markFailed(d.getId(), "无有效收件人", 0));
                continue;
            }

            // 2.3 发送 + 重试
            NotificationSender sender = senders.get(channel.getType());
            if (sender == null) {
                String reason = "不支持的通知类型: " + channel.getType();
                claimed.forEach(d -> deliveries.markFailed(d.getId(), reason, 0));
                continue;
            }
            int attempts = 0;
            Exception last = null;
            for (int i = 0; i < MAX_ATTEMPTS; i++) {
                attempts++;
                try {
                    sender.send(channel, recipients, mailEvents, context);
                    last = null;
                    break;
                } catch (Exception ex) {
                    last = ex;
                    log.warn("告警投递失败（channel={} attempt={}）: {}",
                            channel.getCode(), attempts, ex.getMessage());
                    if (i < MAX_ATTEMPTS - 1) {
                        sleep(BACKOFF_MS[i]);
                    }
                }
            }
            if (last == null) {
                claimed.forEach(d -> deliveries.markSent(d.getId()));
            } else {
                String msg = truncate(last.getMessage() == null
                        ? last.getClass().getSimpleName() : last.getMessage());
                int n = attempts;
                claimed.forEach(d -> deliveries.markFailed(d.getId(), msg, n));
            }
        }
    }

    /** 连渠道都没有：直接落一条 FAILED（channel_code 用空串占位），失败也要看得见。 */
    private void markFail(PlatformAlertEvent e, String channelCode, String error) {
        AlertDelivery d = new AlertDelivery();
        d.setEventId(e.getId());
        d.setChannelCode(channelCode == null ? "" : channelCode);
        d.setAlertStatus(e.getStatus());
        d.setStatus("FAILED");
        d.setCreatedAt(new Date());
        if (deliveries.insertIfAbsent(d) == 1) {
            deliveries.markFailed(d.getId(), error, 0);
        }
    }

    /**
     * 收件人：绑定表的 user_ids（按 CR 名反查）→ 没有则该命名空间所属租户的管理员。
     * 最后统一过滤掉没填邮箱的用户。
     */
    private List<String> recipientsFor(String clusterId, List<PlatformAlertEvent> batch,
                                       NotifyContext context) {
        Set<String> userIds = new LinkedHashSet<>();
        for (PlatformAlertEvent e : batch) {
            String tenantId = context.tenantId(e.getId());
            // CR 名优先取规则自带的 platform_rule **注解** —— 投递在 @Async 线程上跑，
            // 没有请求上下文就没有 Authorization，去集群反查只会拿到 401。
            String ruleName = mapValue(e.getAnnotations(), "platform_rule");
            if (!StringUtils.hasText(ruleName)) {
                ruleName = ruleSourceResolver.crNameOf(clusterId, tenantId, e);
            }
            RuleNotify binding = ruleName == null
                    ? null : bindings.selectByRule(clusterId, e.getNamespace(), ruleName);
            if (binding != null) {
                userIds.addAll(parseIds(binding.getUserIds()));
            } else if (tenantId != null) {
                userIds.addAll(userService.tenantAdminUserIds(tenantId));
            }
        }
        return userService.emailsOf(userIds);
    }

    private List<String> parseIds(String jsonIds) {
        if (!StringUtils.hasText(jsonIds)) {
            return List.of();
        }
        try {
            return json.readValue(jsonIds, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 从 labels / annotations 的 JSON 串里取一个键；空串、坏 JSON 一律返回 null。 */
    private String mapValue(String jsonText, String key) {
        if (!StringUtils.hasText(jsonText)) {
            return null;
        }
        try {
            Map<String, String> m = json.readValue(jsonText,
                    new TypeReference<LinkedHashMap<String, String>>() {});
            return m == null ? null : m.get(key);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= MAX_ERROR_LEN ? s : s.substring(0, MAX_ERROR_LEN);
    }

    /** 退避等待。包级可见/可覆写：测试里替换掉，不真等 36 秒。 */
    protected void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
