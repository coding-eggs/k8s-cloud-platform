package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "命名空间分配查询请求（条件均可选）")
public class AllocationQueryRequest {

    @Schema(description = "租户id，不传查全部")
    private String tenantId;

    @Schema(description = "集群id，不传查全部")
    private String clusterId;
}
