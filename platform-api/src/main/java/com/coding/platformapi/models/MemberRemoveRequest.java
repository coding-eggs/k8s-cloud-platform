package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "移除租户成员请求")
public class MemberRemoveRequest {

    @Schema(description = "租户id：代管必传；自管须与 token 租户一致")
    private String tenantId;

    @Schema(description = "用户id")
    private String userId;
}
