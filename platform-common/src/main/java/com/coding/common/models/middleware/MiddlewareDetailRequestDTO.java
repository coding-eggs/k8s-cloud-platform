package com.coding.common.models.middleware;

import lombok.Data;

/** platform-api → k8s-server：读一个实例的实时对象（详情页用）。 */
@Data
public class MiddlewareDetailRequestDTO {

    private String clusterId;

    /** 中间件 code（决定适配器的 contexts / selector） */
    private String code;

    private String namespace;

    /** 平台生成的 CR 名 */
    private String name;

    /** 平台实例 id（PVC 选择器要用） */
    private String instanceId;

    /** 管理员代管轨的租户；租户轨以 token 为准 */
    private String tenantId;
}
