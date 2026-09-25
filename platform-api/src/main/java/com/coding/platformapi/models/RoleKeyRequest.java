package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "角色id请求")
public class RoleKeyRequest {

    @Schema(description = "角色id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String id;
}
