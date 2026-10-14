package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 新建凭据。{@code username} / {@code secret} 必填（v1 只有 BASIC）。 */
@Data
public class CredentialCreateRequest {

    private String name;

    /** GIT / HARBOR（本批只落已实现的方言值，见 CredentialService.TYPES） */
    private String type;

    private String endpoint;

    /** BASIC */
    private String authType;

    private String username;

    @Schema(description = "密码或令牌明文。**只在这一次请求里存在**，落库即加密，此后永不回显")
    private String secret;

    private String description;

    @Schema(description = "缺省 true")
    private Boolean enabled;
}
