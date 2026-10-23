package com.coding.platformapi.models;

import lombok.Data;

/** {@code POST /middleware/instance/metrics} 的请求体：实例的四张指标图（start/end 为 unix 秒）。 */
@Data
public class MiddlewareMetricsRequest {

    private String instanceId;

    /** 仅管理员代管轨使用；租户轨以 token 为准 */
    private String tenantId;

    private Long start;

    private Long end;
}
