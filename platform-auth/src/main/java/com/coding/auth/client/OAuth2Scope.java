package com.coding.auth.client;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Schema(title = "OAuth2 权限范围")
@Data
@AllArgsConstructor
public class OAuth2Scope {

    @Schema(description = "scope 名称", example = "profile")
    private String name;

    @Schema(description = "scope 描述", example = "查看你的基本个人信息")
    private String description;
}
