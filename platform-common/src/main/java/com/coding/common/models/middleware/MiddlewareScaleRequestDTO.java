package com.coding.common.models.middleware;

import lombok.Data;

/** platform-api → k8s-server：实例扩容。 */
@Data
public class MiddlewareScaleRequestDTO {

    private String clusterId;

    private String code;

    private String namespace;

    private String name;

    /** 实例形态（SINGLE/CLUSTER/...）：扩容规则依赖它，不能只看请求参数 */
    private String mode;

    private Integer replicas;

    private String cpuRequest;
    private String cpuLimit;
    private String memoryRequest;
    private String memoryLimit;

    private String storageSize;

    /** 管理员代管轨的租户 */
    private String tenantId;
}
