package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "集群更新请求（仅名称/描述）")
public class ClusterUpdateRequest {

    @Schema(description = "集群id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String clusterId;

    @Schema(description = "集群名称")
    private String clusterName;

    @Schema(description = "集群描述")
    private String description;
}
