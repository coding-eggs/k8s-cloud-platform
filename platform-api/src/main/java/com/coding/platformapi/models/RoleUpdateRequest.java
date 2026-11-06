package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "修改角色请求：只改名称与描述（编码、角色族不可改，内置角色同样可改名）")
public class RoleUpdateRequest {

    @Schema(description = "角色 id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String id;

    @Schema(description = "角色名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Schema(description = "描述；空 = 清空")
    private String description;
}
