package com.coding.platformapi.models;

import com.coding.common.models.k8s.dto.ChartRefDTO;
import lombok.Data;

/** {@code POST /app/preview} 的请求体（渲染预览，不落盘）。 */
@Data
public class AppPreviewRequest {
    private String clusterId;
    private String namespace;
    private ChartRefDTO chartRef;
    private String values;
}
