package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "修改用户请求（用户名不可改：它是登录名，会话与审计都按它记）")
public class UserUpdateRequest {

    @Schema(description = "用户 id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String id;

    @Schema(description = "显示名称；空 = 清空")
    private String displayName;

    @Schema(description = "邮箱；空 = 清空")
    private String email;

    @Schema(description = "状态：1正常 0禁用；不传 = 不改")
    private Integer status;
}
