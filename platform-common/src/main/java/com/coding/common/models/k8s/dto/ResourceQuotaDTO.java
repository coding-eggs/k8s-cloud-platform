package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 命名空间资源配额（core/v1 ResourceQuota）。
 * <p>
 * 平台单份约定：对象名固定 {@code default}（见 EnumResponseType.QUOTA_NAME_NOT_DEFAULT）。
 * 值为基础单位（cpu=核、memory=字节），Quantity↔BigDecimal 换算在 k8s-core QuantityUtil。
 * 字段为 null = 不约束该项；{@link #convertForUpdate} 的 overlay 语义据此删除 hard 中对应 key，
 * 未建模 key（count/deployments、services.nodeports 等）原样保留。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "命名空间资源配额")
public class ResourceQuotaDTO extends BaseResources {

    @Schema(description = "CPU 上限（核）→ hard.cpu")
    private BigDecimal cpu;

    @Schema(description = "内存上限（字节）→ hard.memory")
    private BigDecimal memory;

    @Schema(description = "Pod 数上限 → hard.pods")
    private Integer pods;

    @Schema(description = "Service 数上限 → hard.services")
    private Integer services;

    @Schema(description = "容器 limit CPU 总和上限（核）→ hard.\"limits.cpu\"")
    private BigDecimal limitsCpu;

    @Schema(description = "容器 limit 内存总和上限（字节）→ hard.\"limits.memory\"")
    private BigDecimal limitsMemory;

    @Schema(description = "容器 request CPU 总和上限（核）→ hard.\"requests.cpu\"")
    private BigDecimal requestsCpu;

    @Schema(description = "容器 request 内存总和上限（字节）→ hard.\"requests.memory\"")
    private BigDecimal requestsMemory;

    @Schema(description = "PVC 数上限 → hard.persistentvolumeclaims")
    private Integer persistentVolumeClaims;

    @Schema(description = "已用量（只读，来自 status.used）")
    private ResourceQuotaUsedDTO used;

    @Schema(description = "该命名空间存在多份 ResourceQuota（只读；平台只管理名为 default 的那份，前端据此告警）")
    private Boolean multiple;

    @Schema(description = "资源版本（只读回传）")
    private String resourceVersion;

    @Schema(description = "创建时间（只读）")
    private String creationTime;

    /** 走 k8s-server admin 命名空间域边界（平台级流程，无租户上下文） */
    @Override
    public String getApiPath() {
        return "/admin/resourcequotas";
    }
}
