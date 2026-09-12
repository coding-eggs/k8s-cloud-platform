package com.coding.platformapi.metrics.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/** 节点当前用量批量查询请求（列表页）：一次查多个 instance。 */
@Data
@Schema(description = "节点当前用量批量查询请求")
public class NodeCurrentRequest {

    @Schema(description = "集群id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String clusterId;

    @Schema(description = "node_exporter 实例列表（<internalIp>:9100）", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> instances;
}
