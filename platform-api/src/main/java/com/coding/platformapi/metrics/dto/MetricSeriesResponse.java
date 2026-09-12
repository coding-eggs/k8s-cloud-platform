package com.coding.platformapi.metrics.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 指标序列响应：unit（单位）+ series[]。百分比由前端本地算，接口只回原始序列 + unit。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "指标序列响应")
public class MetricSeriesResponse {
    @Schema(description = "单位（核 / 字节 / 字节/秒）")
    private String unit;
    @Schema(description = "序列列表")
    private List<MetricSeries> series;
}
