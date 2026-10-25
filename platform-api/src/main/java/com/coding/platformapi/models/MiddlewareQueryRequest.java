package com.coding.platformapi.models;

import lombok.Data;

/** 列表/详情的查询条件。 */
@Data
public class MiddlewareQueryRequest {
    private String clusterId;
    private String code;
    private String instanceId;
    private Long operationId;
    /** 仅管理员代管轨使用 */
    private String tenantId;
    /** 列表按命名空间收窄（空 = 该集群下全部）；顶栏级联选中的那一层 */
    private String namespace;
}
