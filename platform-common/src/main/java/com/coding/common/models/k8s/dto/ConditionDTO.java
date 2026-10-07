package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * 通用 K8s Condition（status.conditions[]）。Calico IPPool / BGP* 等 CRD 的 status 复用此结构。
 * 与 {@link NodeConditionDTO} 同形，但独立成通用类型供非 core 资源使用。
 */
@Data
public class ConditionDTO {
    /** 条件类型（如 Calico IPPool 的 Available / Progressing） */
    private String type;
    /** True / False / Unknown */
    private String status;
    private String reason;
    private String message;
    private String lastTransitionTime;
}
