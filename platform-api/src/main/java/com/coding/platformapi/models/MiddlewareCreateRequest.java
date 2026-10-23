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

    /** Redis 集群形态：每个主分片的从节点数（默认 1；0 = 无从节点） */
    private Integer replicasPerShard;
    private String cpuRequest;
    private String cpuLimit;
    private String memoryRequest;
    private String memoryLimit;
    private String storageSize;
    private String storageClass;
    /** Elasticsearch：是否同时部署 Kibana（ECH 的 Kibana CR，指向本实例） */
    private Boolean kibana;

    /** 是否启用 TLS（ECT 自签证书）；缺省 true。仅 Elasticsearch/Kibana 支持关闭 */
    private Boolean tls;
    /** 仅管理员代管轨使用；租户轨以 token 为准 */
    private String tenantId;
}
