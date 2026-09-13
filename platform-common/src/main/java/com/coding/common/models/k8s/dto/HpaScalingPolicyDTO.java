package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * HPA 扩缩容速率策略（对应 autoscaling/v2 HPAScalingPolicy）
 */
@Data
public class HpaScalingPolicyDTO {

    /** Pods / Percent */
    private String type;

    /** 与 type 对应的数值：Pods=副本增量，Percent=百分比 */
    private Integer value;

    /** 应用该策略的窗口（秒） */
    private Integer periodSeconds;

}
