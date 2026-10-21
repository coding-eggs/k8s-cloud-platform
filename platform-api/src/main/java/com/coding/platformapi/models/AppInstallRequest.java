package com.coding.platformapi.models;

import com.coding.common.models.k8s.dto.ChartRefDTO;
import lombok.Data;

/** {@code POST /app/install} 的请求体。 */
@Data
public class AppInstallRequest {
    private String clusterId;
    private String namespace;
    private String displayName;
    private ChartRefDTO chartRef;
    /** values YAML 文本（表单编辑器产出） */
    private String values;
}
