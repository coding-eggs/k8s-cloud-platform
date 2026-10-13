package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 更新凭据。
 *
 * <p><b>{@code username} / {@code secret} 留空 = 保持不变</b>（逐字段）。这是「永不回显」的必然结果 ——
 * 表单里拿不到旧值，所以「空」只能解读为「不改」。
 *
 * <p><b>例外：{@code authType} 变更时必须重填</b> —— 密文是绑 authType 解释的 JSON，
 * 跨类型沿用会让旧 JSON 的语义对不上。
 */
@Data
public class CredentialUpdateRequest {

    private String id;

    private String name;

    private String type;

    private String endpoint;

    private String authType;

    @Schema(description = "留空 = 保持不变")
    private String username;

    @Schema(description = "留空 = 保持不变")
    private String secret;

    private String description;

    private Boolean enabled;
}
