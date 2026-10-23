package com.coding.common.models.middleware;

import lombok.Data;

/** platform-api → k8s-server：创建一个中间件实例。密码由 api 生成，随请求下发。 */
@Data
public class MiddlewareCreateRequestDTO {

    private String instanceId;

    private String clusterId;

    /** 中间件 code */
    private String code;

    /** SINGLE / CLUSTER / SENTINEL / STANDALONE / HA */
    private String mode;

    private String namespace;

    /** 平台生成的 K8s 对象名 */
    private String name;

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

    /** Elasticsearch：是否同时创建 Kibana CR（其余中间件忽略） */
    private Boolean kibana;

    /** 是否启用 TLS（自签证书）；null/true = 开启。目前只有 Elasticsearch/Kibana 支持关闭 */
    private Boolean tls;

    /** Elasticsearch：是否安装 IK 中文分词器 */
    private Boolean ik;

    /** 平台生成的初始密码；只用于创建 Secret，不落库、不记日志 */
    private String password;

    /** 管理员代管轨显式指定的租户；租户轨以 token 为准（k8s-server 会忽略不一致的值） */
    private String tenantId;
}
