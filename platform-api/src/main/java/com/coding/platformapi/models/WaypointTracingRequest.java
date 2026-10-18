package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** waypoint 链路追踪开关（批次二）。 */
@Data
@Schema(description = "waypoint 链路追踪开关")
public class WaypointTracingRequest {

    @Schema(description = "租户id")
    private String tenantId;

    @Schema(description = "集群id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String clusterId;

    @Schema(description = "命名空间", requiredMode = Schema.RequiredMode.REQUIRED)
    private String namespace;

    @Schema(description = "waypoint Gateway 名", requiredMode = Schema.RequiredMode.REQUIRED)
    private String gatewayName;

    @Schema(description = "true=开启，false=关闭")
    private boolean enabled;
}
