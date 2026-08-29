package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "RBAC 模板id请求")
public class TemplateKeyRequest {

    @Schema(description = "模板id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String id;
}
