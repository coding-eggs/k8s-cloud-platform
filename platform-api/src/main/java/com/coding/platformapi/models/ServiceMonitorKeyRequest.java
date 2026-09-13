package com.coding.platformapi.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "ServiceMonitor 定位：集群 + 命名空间 + 名称（Prometheus discovery 查询用）")
public class ServiceMonitorKeyRequest {

    @Schema(description = "集群id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String clusterId;

    @Schema(description = "命名空间名", requiredMode = Schema.RequiredMode.REQUIRED)
    private String namespace;

    @Schema(description = "ServiceMonitor 名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;
}
