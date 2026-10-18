package com.coding.common.models.k8s.dto;

import lombok.Data;

/** collector 的 ServiceMonitor 下发参数（OTel 批次一）。 */
@Data
public class OtelServiceMonitorRequestDTO {
    private String clusterId;
    /** collector 所在命名空间（monitor） */
    private String namespace;
}
