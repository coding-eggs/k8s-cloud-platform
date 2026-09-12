package com.coding.platformapi.metrics.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 节点监控指标查询请求（详情页 4 图）。instance = {@code "<internalIp>:9100"}，由前端从 node.internalIp 拼。 */
@Data
@Schema(description = "节点监控指标查询请求")
public class NodeMetricsRequest {

    @Schema(description = "集群id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String clusterId;

    @Schema(description = "node_exporter 实例（<internalIp>:9100）", requiredMode = Schema.RequiredMode.REQUIRED, example = "10.0.0.5:9100")
    private String instance;

    @Schema(description = "起始时间（unix 秒）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1756800000")
    private long start;

    @Schema(description = "结束时间（unix 秒）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1756801800")
    private long end;
}
