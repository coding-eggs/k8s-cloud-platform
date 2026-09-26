package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "创建租户请求")
public class TenantCreateRequest {

    @Schema(description = "租户名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "coding-test")
    private String name;

    @Schema(description = "租户标识（K8s SA 裸名，小写字母/数字/-，≤60 字符）", requiredMode = Schema.RequiredMode.REQUIRED, example = "coding-test")
    private String serviceAccount;

    @Schema(description = "状态：1 启用（默认），0 禁用")
    private Integer status;

    @Schema(description = "owner 用户id：非空时同租户创建即把该用户加入成员并授予内置 tenant-admin 角色")
    private String ownerUserId;
}
