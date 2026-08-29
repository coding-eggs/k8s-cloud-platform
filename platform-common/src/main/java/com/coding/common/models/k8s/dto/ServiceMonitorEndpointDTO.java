package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * ServiceMonitor 端点（基础表单字段）
 */
@Data
public class ServiceMonitorEndpointDTO {

    /** 端口名或端口号 */
    private String port;

    /** 抓取路径，缺省 /metrics */
    private String path;

    /** 抓取间隔，如 30s */
    private String interval;

}
