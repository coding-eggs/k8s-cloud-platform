package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * PVC 扩容请求体（列表页行操作里的「调整容量」）。
 *
 * <p>**只有容量**，没有别的字段 —— 这正是独立于 {@code PUT /resource/pvcs/{name}} 的意义：
 * 那个端点是"更新这个 PVC"（同步标签），而扩容是另一件量级的事。
 */
@Data
@Schema(description = "PVC 扩容请求")
public class PvcResizeRequest {

    @Schema(description = "租户id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String tenantId;

    @Schema(description = "集群id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String clusterId;

    @Schema(description = "命名空间", requiredMode = Schema.RequiredMode.REQUIRED)
    private String namespace;

    /** 目标容量（基础单位：字节）。⚠️ 必须**大于**当前值 —— K8s 不支持缩容。 */
    @Schema(description = "目标容量（字节，基础单位）", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal storage;
}
