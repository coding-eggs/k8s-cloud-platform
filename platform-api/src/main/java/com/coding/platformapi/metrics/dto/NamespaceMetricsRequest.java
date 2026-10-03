package com.coding.platformapi.metrics.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 命名空间监控指标查询请求（概览页 4 图）。clusterId + namespace + 时间范围；集群级、无租户。 */
@Data
@Schema(description = "命名空间监控指标查询请求")
public class NamespaceMetricsRequest {

    @Schema(description = "集群id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String clusterId;

    @Schema(description = "命名空间名", requiredMode = Schema.RequiredMode.REQUIRED, example = "order-prod")
    private String namespace;

    @Schema(description = "起始时间（unix 秒）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1756800000")
    private long start;

    @Schema(description = "结束时间（unix 秒）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1756801800")
    private long end;
}
