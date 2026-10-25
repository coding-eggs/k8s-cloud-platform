package com.coding.platformapi.models;

import lombok.Data;

/** {@code POST /middleware/instance/create} 的请求体。 */
@Data
public class MiddlewareCreateRequest {
    private String clusterId;
    private String code;
    private String mode;
    private String namespace;

    /** 由应用画布创建时：来源应用与节点 key；商店直接创建时为空 */
    private String appId;

    private String appNodeId;
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

    /** Elasticsearch：是否安装 IK 中文分词器（initContainer 下载 analysis-ik） */
    private Boolean ik;

    /** 是否采集业务指标（默认关）：装 exporter / 打开指标端点 + 建 Service & ServiceMonitor */
    private Boolean metricsEnabled;

    /** Milvus：etcd 节点数（1~3；null = 单节点 1、集群 3） */
    private Integer etcdReplicas;

    /** Milvus：对象存储来源，PLATFORM / CUSTOM */
    private String objectStorageType;

    /** Milvus：S3 endpoint（CUSTOM 由用户填；PLATFORM 由平台填） */
    private String objectStorageEndpoint;

    /** Milvus：桶名（PLATFORM 由平台填；CUSTOM 由用户填） */
    private String objectStorageBucket;

    /** Milvus：S3 AccessKey（只用于写 Secret，不落库） */
    private String objectStorageAccessKey;

    /** Milvus：S3 SecretKey（只用于写 Secret，不落库） */
    private String objectStorageSecretKey;

    /** Milvus：S3 是否走 TLS（null/false = 明文） */
    private Boolean objectStorageUseSsl;

    /** Milvus：单节点本地数据盘（默认 10Gi；集群形态不使用） */
    private String localStorageSize;
    /** 仅管理员代管轨使用；租户轨以 token 为准 */
    private String tenantId;
}
