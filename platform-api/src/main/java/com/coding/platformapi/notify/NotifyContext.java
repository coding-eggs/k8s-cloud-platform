package com.coding.platformapi.notify;

import java.util.Map;

/**
 * 通知正文用的**上下文**：告警 payload 里没有、但邮件里必须说清楚的东西
 * （哪个集群、哪个租户、这条规则长什么样）。
 *
 * <p>为什么单独传而不是塞进 {@code PlatformAlertEvent}：表达式、租户名都是**投递时才算出来**的，
 * 塞进实体会让「落库的行」和「渲染所需的行」混成一个东西，还会漏进列表 API 的 JSON。
 *
 * @param clusterName 集群显示名（可能为 null，正文回落成 clusterId）
 * @param tenantIds   eventId → 租户 id（命名空间没分配给任何租户时缺键）
 * @param tenantNames eventId → 租户显示名
 * @param expressions eventId → PromQL 表达式（Prometheus 查不到时缺键）
 */
public record NotifyContext(String clusterName,
                            Map<Long, String> tenantIds,
                            Map<Long, String> tenantNames,
                            Map<Long, String> expressions) {

    public static final NotifyContext EMPTY =
            new NotifyContext(null, Map.of(), Map.of(), Map.of());

    /** eventId 为 null（没回查到 id 的行）时直接返回 null —— `Map.of()` 不接受 null 键，会 NPE。 */
    public String tenantId(Long eventId) {
        return eventId == null ? null : tenantIds.get(eventId);
    }

    public String tenantName(Long eventId) {
        return eventId == null ? null : tenantNames.get(eventId);
    }

    public String expression(Long eventId) {
        return eventId == null ? null : expressions.get(eventId);
    }
}
