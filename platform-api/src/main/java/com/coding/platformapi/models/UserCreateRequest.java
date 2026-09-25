package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "创建用户请求")
public class UserCreateRequest {

    @Schema(description = "用户名（唯一）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String username;

    @Schema(description = "明文密码（BCrypt 加密后存储）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;

    @Schema(description = "显示名称")
    private String displayName;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "状态：1正常 0禁用，默认 1")
    private Integer status;
}
