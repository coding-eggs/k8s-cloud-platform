package com.coding.platformapi.metrics.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 节点当前用量（列表页）：CPU/内存使用率百分比。无数据为 null（前端显示 "-"）。 */
@Data
@Schema(description = "节点当前用量")
public class NodeCurrentMetric {
    @Schema(description = "CPU 使用率 %")
    private Double cpuPercent;
    @Schema(description = "内存使用率 %")
    private Double memPercent;
}
