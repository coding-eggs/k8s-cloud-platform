package com.coding.platformapi.metrics.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 指标数据点：ts（unix 秒）+ value。 */
@Data
@Schema(description = "指标数据点")
public class MetricPoint {
    @Schema(description = "时间戳（unix 秒）")
    private long ts;
    @Schema(description = "值")
    private double value;
}
