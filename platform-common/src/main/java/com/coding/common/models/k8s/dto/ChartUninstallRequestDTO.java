package com.coding.common.models.k8s.dto;

import lombok.Data;

/** {@code POST /chart/uninstall} 的请求体。 */
@Data
public class ChartUninstallRequestDTO {
    private String clusterId;
    private String appId;
    private String releaseName;
    private String namespace;
}
