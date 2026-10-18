package com.coding.common.models.k8s.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 某条 Telemetry 的当前状态（waypoint 编辑页回填用）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OtelTelemetryStateDTO {
    private boolean enabled;
    /** 已启用时的采样率；未启用 → null */
    private Integer samplingPercentage;
}
