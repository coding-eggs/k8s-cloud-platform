package com.coding.platformapi.metrics.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/** 一条指标序列（图上的一个图例）。 */
@Data
@Schema(description = "指标序列（一个图例）")
public class MetricSeries {
    @Schema(description = "图例名（如 RX / TX / 读 / 写 / 用量）")
    private String legend;
    @Schema(description = "数据点（按时间升序）")
    private List<MetricPoint> points;
}
