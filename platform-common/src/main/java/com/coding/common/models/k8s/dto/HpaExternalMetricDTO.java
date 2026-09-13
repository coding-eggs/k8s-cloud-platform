package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.Map;

/**
 * HPA External 指标（对应 autoscaling/v2 ExternalMetricSource：集群外指标，如队列长度）。
 * <p>metricSelector 映射到 {@code external.metric.selector}（fabric8 MetricIdentifier 类型化字段）。
 */
@Data
public class HpaExternalMetricDTO {

    /** 指标名 */
    private String metricName;

    /** 指标标签选择器（matchLabels，可空）→ external.metric.selector */
    private Map<String, String> metricSelector;

    /** 目标（type 通常为 Value，可带 averageValue） */
    private HpaMetricTargetDTO target;

}
