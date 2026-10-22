package com.coding.common.models.middleware;

import lombok.Data;

/** platform-api → k8s-server：实例扩容。 */
@Data
public class MiddlewareScaleRequestDTO {

    private String clusterId;

    private String code;

    private String namespace;

    private String name;

    private Integer replicas;

    private String cpuRequest;
    private String cpuLimit;
    private String memoryRequest;
    private String memoryLimit;

    private String storageSize;
}
