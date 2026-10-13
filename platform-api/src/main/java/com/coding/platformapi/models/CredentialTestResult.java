package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 测试连接的结果。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CredentialTestResult {

    private Boolean ok;

    /** 给用户看的原因。凭据类失败一律**二义文案**（spec §5.4） */
    private String message;

    @Schema(description = "非空表示「对端要求改用这个地址」，前端应提供一键填入")
    private String suggestedUrl;
}
