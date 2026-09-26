package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "用户↔平台角色授予/回收请求")
public class UserRoleGrantRequest {

    @Schema(description = "用户id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String userId;

    @Schema(description = "平台角色id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String roleId;
}
