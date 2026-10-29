package com.coding.platformapi.models;

import lombok.Data;

/**
 * 实例索引查询（目前只有 Elasticsearch 支持）。
 *
 * <p>{@code instanceId} 定位台账，集群/命名空间/CR 名都从台账取 —— 不让前端传，
 * 免得出现「拿 A 实例的 id 查 B 命名空间的索引」。
 */
@Data
public class MiddlewareIndexRequest {

    private String instanceId;

    /** 索引名；列表接口忽略，详情接口必填 */
    private String index;

    /** 管理员代管轨的租户；租户轨以 token 为准 */
    private String tenantId;
}
