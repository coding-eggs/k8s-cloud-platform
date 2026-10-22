package com.coding.common.models.middleware;

import lombok.Data;

/** platform-api → k8s-server：删除实例。 */
@Data
public class MiddlewareDeleteRequestDTO {

    private String clusterId;

    private String code;

    private String namespace;

    private String name;

    /** 实例台账 id：用于按平台标签删 PVC */
    private String instanceId;

    /** true = 保留 PVC（数据不删）；false = 一并删除 */
    private boolean keepPvc;

    /** 管理员代管轨的租户 */
    private String tenantId;
}
