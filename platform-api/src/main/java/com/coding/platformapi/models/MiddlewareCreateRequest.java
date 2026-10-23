package com.coding.platformapi.models;

import lombok.Data;

/** {@code POST /middleware/instance/create} 的请求体。 */
@Data
public class MiddlewareCreateRequest {
    private String clusterId;
    private String code;
    private String mode;
    private String namespace;
    private String displayName;
    private String version;
    private Integer replicas;
    private String cpuRequest;
    private String cpuLimit;
    private String memoryRequest;
    private String memoryLimit;
    private String storageSize;
    private String storageClass;
    /** Elasticsearch：是否同时部署 Kibana（ECH 的 Kibana CR，指向本实例） */
    private Boolean kibana;
    /** 仅管理员代管轨使用；租户轨以 token 为准 */
    private String tenantId;
}
