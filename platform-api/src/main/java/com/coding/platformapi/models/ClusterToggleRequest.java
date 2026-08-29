package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "集群启用/禁用请求")
public class ClusterToggleRequest {

    @Schema(description = "集群id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String clusterId;

    @Schema(description = "是否启用：1 启用，0 禁用", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer enabled;
}
