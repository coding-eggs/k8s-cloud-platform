package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "权限点id请求")
public class PermissionKeyRequest {

    @Schema(description = "权限点行id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String id;
}
