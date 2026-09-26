package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "创建角色请求")
public class RoleCreateRequest {

    @Schema(description = "角色名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Schema(description = "角色编码（platform_role 内唯一）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String code;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "角色族：PLATFORM / TENANT", requiredMode = Schema.RequiredMode.REQUIRED)
    private String scope;
}
