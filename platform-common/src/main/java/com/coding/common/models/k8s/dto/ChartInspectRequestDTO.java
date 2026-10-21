package com.coding.common.models.k8s.dto;

import lombok.Data;

/** {@code POST /chart/inspect} 的请求体。 */
@Data
public class ChartInspectRequestDTO {
    private ChartRefDTO chart;
}
