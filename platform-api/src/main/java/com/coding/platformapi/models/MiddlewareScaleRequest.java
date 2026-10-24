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

    /** 业务指标：true = 幂等开启（装 exporter + 建采集资源）；单向，不支持关闭 */
    private Boolean metricsEnabled;

    private String tenantId;
}
