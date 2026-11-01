package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 按 URL 反查的入参：实际请求方法 + 路径（不带 query/hash，须以 / 开头）。 */
@Data
@Schema(description = "按 URL 反查权限覆盖")
public class PermissionMatchRequest {

    @Schema(description = "HTTP 方法：GET / POST / PUT / DELETE / PATCH",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String method;

    @Schema(description = "实际请求路径（以 / 开头；不含 query/hash）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String path;
}
