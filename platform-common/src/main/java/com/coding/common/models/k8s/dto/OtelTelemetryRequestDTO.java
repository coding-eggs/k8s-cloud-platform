package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * Telemetry CR 的下发参数（批次二）。
 *
 * <p>只服务 ingress gateway 与 waypoint：{@code enabled=false} 时删除该 CR；
 * CRD 或目标 Gateway 不存在时服务端返回 false（跳过），不视为失败。
 */
@Data
public class OtelTelemetryRequestDTO {
    private String clusterId;
    /** Telemetry 与目标 Gateway 所在命名空间 */
    private String namespace;
    /** Telemetry CR 名（平台固定，如 platform-tracing-waypoint） */
    private String name;
    /** targetRefs 指向的 Gateway 名 */
    private String gatewayName;
    /** 采样率百分比 1..100 */
    private Integer samplingPercentage;
    /** true = 创建/更新；false = 删除 */
    private Boolean enabled;
}
