package com.coding.common.models.middleware;

import lombok.Data;

/** platform-api → k8s-server：读一个实例的索引（ES）或等价的数据对象。 */
@Data
public class MiddlewareIndexRequestDTO {

    private String clusterId;

    /** 中间件 code；当前只有 elasticsearch 支持 */
    private String code;

    private String namespace;

    /** 平台生成的 CR 名 */
    private String name;

    /** 平台实例 id（访问边界判定要用） */
    private String instanceId;

    /** 管理员代管轨的租户；租户轨以 token 为准 */
    private String tenantId;

    /** 索引名；列表接口忽略，详情接口必填 */
    private String index;
}
