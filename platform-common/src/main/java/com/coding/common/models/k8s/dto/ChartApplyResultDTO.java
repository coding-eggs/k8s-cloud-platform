package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.List;

/** 安装结果；violations 是**装后复核**（helm get manifest）的结果。 */
@Data
public class ChartApplyResultDTO {
    private boolean success;
    private String message;
    private String logTail;
    private List<ChartViolationDTO> violations;
}
