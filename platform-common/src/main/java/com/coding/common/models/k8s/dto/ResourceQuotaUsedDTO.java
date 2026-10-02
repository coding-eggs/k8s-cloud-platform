package com.coding.common.models.k8s.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * ResourceQuota 已用量（只读，来自 spec 侧的 status.used），字段与 {@link ResourceQuotaDTO} 的约束项一一对应。
 * 基础单位：cpu=核、memory=字节；计数=个。未建模的 hard key（如 count/deployments）不在此列，
 * 由 converter revert 时丢弃（平台只回显已建模项）。
 */
@Data
@Schema(description = "命名空间资源配额已用量（只读）")
public class ResourceQuotaUsedDTO {

    @Schema(description = "已用 Pod 数")
    private Integer pods;

    @Schema(description = "已用容器 limit CPU 总和（核）")
    private BigDecimal limitsCpu;

    @Schema(description = "已用容器 limit 内存总和（字节）")
    private BigDecimal limitsMemory;

    @Schema(description = "已用容器 request CPU 总和（核）")
    private BigDecimal requestsCpu;

    @Schema(description = "已用容器 request 内存总和（字节）")
    private BigDecimal requestsMemory;

    @Schema(description = "已用 PVC 数")
    private Integer persistentVolumeClaims;
}
