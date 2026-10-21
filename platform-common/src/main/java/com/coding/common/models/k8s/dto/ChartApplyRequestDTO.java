package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.Map;

/** {@code POST /chart/apply} 的请求体（安装与升级同一形状）。 */
@Data
public class ChartApplyRequestDTO {
    private String clusterId;
    private String appId;
    private String releaseName;
    private String namespace;
    private boolean strict;
    private ChartRefDTO chart;
    private String values;
    private Map<String, String> labels;
}
