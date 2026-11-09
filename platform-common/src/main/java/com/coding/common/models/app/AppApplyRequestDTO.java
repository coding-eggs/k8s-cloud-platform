package com.coding.common.models.app;

import lombok.Data;

import java.util.Set;

/** platform-api → k8s-server：把合并后的清单 SSA 到集群。 */
@Data
public class AppApplyRequestDTO {

    private String clusterId;

    private String manifest;

    /** 非空：只下发这些 kind（ROUTE 阶段只下发路由） */
    private Set<String> onlyKinds;

    /** 非空：跳过这些 kind（APPLY 阶段先不下发路由，等工作负载就绪） */
    private Set<String> skipKinds;
}
