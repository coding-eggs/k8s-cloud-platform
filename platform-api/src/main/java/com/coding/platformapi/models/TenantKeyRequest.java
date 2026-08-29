package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "租户id请求")
public class TenantKeyRequest {

    @Schema(description = "租户id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String id;
}
