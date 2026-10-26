package com.coding.common.models.middleware;

import lombok.Data;

/** 列实例账号的请求。 */
@Data
public class MiddlewareUserListRequestDTO {

    private String instanceId;

    private String clusterId;

    private String namespace;

    private String name;

    /** 管理员代管轨的租户 */
    private String tenantId;
}
