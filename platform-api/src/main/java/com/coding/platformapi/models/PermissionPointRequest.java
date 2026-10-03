package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 权限点行字段（create 全量提交；update 带 id 全量重存该行）
 */
@Data
@Schema(description = "权限点行字段")
public class PermissionPointRequest {

    @Schema(description = "行id（update 必填，create 忽略）")
    private String id;

    @Schema(description = "权限域：API / K8S / Page", requiredMode = Schema.RequiredMode.REQUIRED)
    private String domain;

    @Schema(description = "资源：API 域 = URL 模式（Ant 通配，如 /xxx/**）；K8S/Page 域 = 资源名/页面标识",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String resource;

    @Schema(description = "动作：API 域 = HTTP 方法（GET/POST/PUT/DELETE/PATCH/*）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String action;

    @Schema(description = "权限码（须以 platform: 或 tenant: 开头；同 code 可覆盖多行 URL，ANY-of）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String code;

    @Schema(description = "说明")
    private String description;
}
