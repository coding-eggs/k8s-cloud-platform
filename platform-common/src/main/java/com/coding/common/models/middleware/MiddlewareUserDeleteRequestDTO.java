package com.coding.common.models.middleware;

import lombok.Data;

/** 删除实例里的一个账号（内置账号拒绝删除）。 */
@Data
public class MiddlewareUserDeleteRequestDTO {

    private String instanceId;

    private String clusterId;

    private String namespace;

    private String name;

    private String username;

    /** 管理员代管轨的租户 */
    private String tenantId;
}
