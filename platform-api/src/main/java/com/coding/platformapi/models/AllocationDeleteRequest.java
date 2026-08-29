package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "取消命名空间分配请求")
public class AllocationDeleteRequest {

    @Schema(description = "租户id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String tenantId;

    @Schema(description = "集群id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String clusterId;

    @Schema(description = "命名空间名", requiredMode = Schema.RequiredMode.REQUIRED)
    private String namespace;
}
