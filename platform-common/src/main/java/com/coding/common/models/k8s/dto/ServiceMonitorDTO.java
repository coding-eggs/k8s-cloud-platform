package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

/**
 * ServiceMonitor DTO（monitoring.coreos.com/v1 CRD，基础表单字段）
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ServiceMonitorDTO extends BaseResources {

    /** 目标 Service 选择器 matchLabels */
    private Map<String, String> matchLabels;

    private List<ServiceMonitorEndpointDTO> endpoints;

    /** 创建时间（仅查询返回，ISO-8601 字符串） */
    private String creationTime;

    @Override
    public String getApiPath() {
        return "/resources/servicemonitors";
    }

}
