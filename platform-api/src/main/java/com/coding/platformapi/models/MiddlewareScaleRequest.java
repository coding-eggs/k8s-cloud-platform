package com.coding.platformapi.models;

import lombok.Data;

/** {@code POST /middleware/instance/scale} 的请求体。 */
@Data
public class MiddlewareScaleRequest {
    private String instanceId;
    private Integer replicas;
    private String cpuRequest;
    private String cpuLimit;
    private String memoryRequest;
    private String memoryLimit;
    private String storageSize;
    private String tenantId;
}
