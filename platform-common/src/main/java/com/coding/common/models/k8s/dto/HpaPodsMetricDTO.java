package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * HPA Pods 指标（对应 autoscaling/v2 PodsMetricSource：一组 Pod 的平均自定义指标）。
 * <p>注：fabric8 7.6.1 的 {@code PodsMetricSource} 未暴露类型化的顶层 selector，故此处不建模 selector。
 */
@Data
public class HpaPodsMetricDTO {

    /** 指标名（如 requests-per-second） */
    private String metricName;

    /** 目标（type 通常为 AverageValue） */
    private HpaMetricTargetDTO target;

}
