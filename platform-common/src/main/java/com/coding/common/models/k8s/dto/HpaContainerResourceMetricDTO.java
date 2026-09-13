package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * HPA ContainerResource 指标（对应 autoscaling/v2 ContainerResourceMetricSource：指定容器的 cpu/memory）
 */
@Data
public class HpaContainerResourceMetricDTO {

    /** 容器名 */
    private String container;

    /** 资源名，如 cpu / memory */
    private String name;

    /** 目标 */
    private HpaMetricTargetDTO target;

}
