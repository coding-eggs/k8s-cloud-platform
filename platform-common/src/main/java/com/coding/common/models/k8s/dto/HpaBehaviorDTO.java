package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * HPA 扩缩容行为（对应 autoscaling/v2 HorizontalPodAutoscalerBehavior，v1 无此字段）
 */
@Data
public class HpaBehaviorDTO {

    /** 扩容规则 */
    private HpaScalingRulesDTO scaleUp;

    /** 缩容规则 */
    private HpaScalingRulesDTO scaleDown;

}
