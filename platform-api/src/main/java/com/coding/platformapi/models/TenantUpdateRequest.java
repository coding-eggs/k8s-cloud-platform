package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "租户更新请求（名称/状态）")
public class TenantUpdateRequest {

    @Schema(description = "租户id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String id;

    @Schema(description = "租户名称")
    private String name;

    @Schema(description = "状态：1 启用，0 禁用")
    private Integer status;
}
