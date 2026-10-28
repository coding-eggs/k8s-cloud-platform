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

    /** Elasticsearch：是否安装 IK 分词器（true = 幂等安装；false/null = 不处理，单向不回退） */
    private Boolean ik;

    /**
     * Elasticsearch：Kibana 附属实例（**唯一可双向的编辑项**）。
     * null = 不处理；true = 确保存在（缺则按线上 ES 的版本/TLS 补建）；false = 确保不存在（删除 Kibana CR）。
     */
    private Boolean kibana;

    /** 业务指标：true = 幂等开启（装 exporter + 建采集资源）；单向，不支持关闭 */
    private Boolean metricsEnabled;

    private String tenantId;
}
