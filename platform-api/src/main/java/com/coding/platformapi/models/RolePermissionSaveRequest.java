package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "角色权限勾选保存请求（全量重存）")
public class RolePermissionSaveRequest {

    @Schema(description = "角色id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String roleId;

    @Schema(description = "勾选的权限点 code 列表（空列表 = 清空该角色全部权限）")
    private List<String> permissionCodes;
}
