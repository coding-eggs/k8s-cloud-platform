package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "授予/回收租户成员角色请求")
public class MemberRoleRequest {

    @Schema(description = "租户id：代管必传；自管须与 token 租户一致")
    private String tenantId;

    @Schema(description = "用户id")
    private String userId;

    @Schema(description = "角色id（TENANT 域角色）")
    private String roleId;
}
