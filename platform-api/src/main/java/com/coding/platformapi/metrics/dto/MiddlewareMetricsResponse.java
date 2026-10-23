package com.coding.platformapi.metrics.dto;

import java.util.List;

/**
 * 中间件实例的监控数据：四张**资源用量**图 + 若干条**业务指标**（按中间件类型声明）。
 *
 * <p>口径：该实例 selector 命中的**全部 Pod** 聚合（含 Operator 追加的侧车容器，不含 netns 影子容器）。
 * CPU=核、内存=字节、网络/磁盘=字节/秒；业务指标的单位由声明给出。
 */
public record MiddlewareMetricsResponse(
        MetricSeriesResponse cpu,
        MetricSeriesResponse memory,
        MetricSeriesResponse network,
        MetricSeriesResponse disk,
        List<BusinessMetric> business) {

    public MiddlewareMetricsResponse {
        business = business == null ? List.of() : business;
    }

    /**
     * 一条业务指标。
     *
     * @param key   稳定标识（前端 key 用）
     * @param label 展示名（就绪消息 / 复制延迟 …）
     * @param data  序列（unit 已按声明填好）
     */
    public record BusinessMetric(String key, String label, MetricSeriesResponse data) {
    }

    /** 没有任何 Pod（还没起来 / 已删）时的空结果：前端各图都显示「无数据」。 */
    public static MiddlewareMetricsResponse empty() {
        return new MiddlewareMetricsResponse(
                new MetricSeriesResponse("核", List.of()),
                new MetricSeriesResponse("字节", List.of()),
                new MetricSeriesResponse("字节/秒", List.of()),
                new MetricSeriesResponse("字节/秒", List.of()),
                List.of());
    }

    /** 补上业务指标（资源用量那四张已算好）。 */
    public MiddlewareMetricsResponse withBusiness(List<BusinessMetric> metrics) {
        return new MiddlewareMetricsResponse(cpu, memory, network, disk, metrics);
    }
}
