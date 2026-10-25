package com.coding.common.models.app;

import lombok.Data;

import java.util.List;
import java.util.Map;

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

    /** 节点 key → 要注入的环境变量（由 platform-api 的依赖解析产出） */
    private Map<String, List<AppInjectDTO>> injectByNode;
}
