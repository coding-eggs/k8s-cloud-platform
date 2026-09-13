package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * HPA Resource 指标（对应 autoscaling/v2 ResourceMetricSource：整 Pod 的 cpu/memory 等）
 */
@Data
public class HpaResourceMetricDTO {

    /** 资源名，如 cpu / memory */
    private String name;

    /** 目标 */
    private HpaMetricTargetDTO target;

}
