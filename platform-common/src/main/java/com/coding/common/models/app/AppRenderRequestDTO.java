package com.coding.common.models.app;

import lombok.Data;

/** platform-api → k8s-server：渲染一个应用的图。 */
@Data
public class AppRenderRequestDTO {

    private String clusterId;

    private String namespace;

    private String appId;

    private String appSlug;

    /** 目标版本号（只用于注解与快照标识，不参与渲染决策） */
    private Integer revision;

    private AppSpecDTO spec;
}
