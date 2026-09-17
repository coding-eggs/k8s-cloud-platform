package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * HPA 指标目标（对应 autoscaling/v2 MetricTarget；五类 metric 共用，仅允许的 type 值不同）
 */
@Data
public class HpaMetricTargetDTO {

    /** Utilization / AverageValue / Value */
    private String type;

    /** type=Utilization 时的平均利用率百分比（0-100） */
    private Integer averageUtilization;

    /** type=AverageValue/Value 时的目标值（基础单位：CPU=核数、内存=字节） */
    private BigDecimal value;

    /** type=Value 且 External 时可选的平均值（基础单位数值） */
    private BigDecimal averageValue;

}
