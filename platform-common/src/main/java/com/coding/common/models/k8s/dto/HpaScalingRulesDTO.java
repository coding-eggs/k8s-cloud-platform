package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * HPA 扩缩容行为规则（对应 autoscaling/v2 HPAScalingRules，behavior.scaleUp / scaleDown）
 */
@Data
public class HpaScalingRulesDTO {

    /** 稳定期（秒）：窗口内的中间状态不计入决策 */
    private Integer stabilizationWindowSeconds;

    /** Max / Min / Disabled：多条 policy 结果如何取舍 */
    private String selectPolicy;

    /** 速率策略列表 */
    private java.util.List<HpaScalingPolicyDTO> policies;

}
