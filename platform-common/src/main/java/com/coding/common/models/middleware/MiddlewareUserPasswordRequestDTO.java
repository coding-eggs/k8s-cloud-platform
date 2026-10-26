package com.coding.common.models.middleware;

import lombok.Data;

/** 改某个账号的口令（新口令留空 = 平台生成）。 */
@Data
public class MiddlewareUserPasswordRequestDTO {

    private String instanceId;

    private String clusterId;

    private String namespace;

    private String name;

    private String username;

    private String newPassword;

    /** 管理员代管轨的租户 */
    private String tenantId;
}
