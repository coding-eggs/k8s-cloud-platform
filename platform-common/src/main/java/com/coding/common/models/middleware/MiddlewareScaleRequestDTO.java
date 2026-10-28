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

    /** Elasticsearch：是否安装 IK 分词器（幂等；null/false = 不处理） */
    private Boolean ik;

    /**
     * Elasticsearch：Kibana 附属实例的**三态**期望（唯一可双向的编辑项）。
     * <ul>
     *   <li>{@code null} = 不处理（缺省；老前端/其它中间件都走这条）</li>
     *   <li>{@code true} = 确保存在 —— 不存在时按线上 ES CR 的版本/TLS 补建</li>
     *   <li>{@code false} = 确保不存在 —— 存在则删除 Kibana CR（ES 与数据不受影响）</li>
     * </ul>
     */
    private Boolean kibana;

    /** 实例台账 id（采集资源的平台标签要用） */
    private String instanceId;

    /**
     * 业务指标：true = 幂等开启（装 exporter / 建 Service + ServiceMonitor）。
     * null/false = 不处理 —— **单向，不回退**（关掉 exporter 需要改 CR 并重启，不在编辑范围）。
     */
    private Boolean metricsEnabled;

    /** Nacos（chart）重新 upgrade 时要用；其它中间件忽略 */
    private String storageClass;

    /** 管理员代管轨的租户 */
    private String tenantId;

    /** 镜像仓库（同创建请求）：扩缩容重写 Pod 模板时不能把镜像丢回上游 */
    private String imageRegistryHost;
    private String imageRegistryProject;
}
