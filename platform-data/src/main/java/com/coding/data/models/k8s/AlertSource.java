package com.coding.data.models.k8s;

/**
 * 告警的**来源规则**：由哪个 PrometheusRule 的哪个规则组定义的。
 *
 * <p>不是表字段 —— 查询时按 {@code (namespace, alertname)} 反查 PrometheusRule 补上，
 * 所以规则改名/删除之后的历史告警查不到（前端显示「—」），这是反查方案的固有边界。
 */
public record AlertSource(String cr, String group) {
}
