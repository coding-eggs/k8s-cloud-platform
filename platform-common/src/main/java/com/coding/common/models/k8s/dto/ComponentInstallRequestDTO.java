package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.List;

/** {@code POST /components/apply} 的请求体。 */
@Data
public class ComponentInstallRequestDTO {
    private String clusterId;
    private String componentCode;
    /** INSTALL / UPGRADE（只用于记录与日志，执行路径相同） */
    private String action;
    private List<ReleaseInstallSpecDTO> releases;
}
