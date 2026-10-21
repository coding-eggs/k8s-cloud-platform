package com.coding.common.models.k8s.dto;

import lombok.Data;

/** {@code POST /chart/preview} 的请求体。 */
@Data
public class ChartPreviewRequestDTO {
    private String clusterId;
    private String namespace;
    /** true = 租户路径（集群级对象/hook/未知 kind 阻断） */
    private boolean strict;
    private ChartRefDTO chart;
    private String values;
}
