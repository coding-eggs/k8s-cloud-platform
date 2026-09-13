package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.Map;

/**
 * HPA Object 指标（对应 autoscaling/v2 ObjectMetricSource：某个 K8s 对象的自定义指标，如 Ingress 的 req/s）。
 * <p>selector 映射到 {@code object.metric.selector}（fabric8 MetricIdentifier 类型化字段）。
 */
@Data
public class HpaObjectMetricDTO {

    /** 被描述的对象引用 */
    private HpaCrossVersionObjectReferenceDTO describedObject;

    /** 指标名 */
    private String metricName;

    /** 指标标签选择器（matchLabels，可空）→ object.metric.selector */
    private Map<String, String> selector;

    /** 目标（type 通常为 Value） */
    private HpaMetricTargetDTO target;

}
