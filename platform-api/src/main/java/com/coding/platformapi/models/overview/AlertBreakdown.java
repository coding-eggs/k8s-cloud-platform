package com.coding.platformapi.models.overview;

import java.util.List;
import java.util.Map;

/** 告警计数及其定位范围。namespace 为空时表示无命名空间标签。 */
public record AlertBreakdown(long total, Map<String, Long> byStatus, List<NamespaceAlerts> byNamespace) {
    public record NamespaceAlerts(String clusterId, String namespace, long total, Map<String, Long> byStatus) {}
}
