package com.coding.common.models.app;

import lombok.Data;

/** platform-api → k8s-server：把合并后的清单 SSA 到集群。 */
@Data
public class AppApplyRequestDTO {

    private String clusterId;

    private String manifest;
}
