package com.coding.common.models.middleware;

import lombok.Data;

/** 新增实例账号（口令留空 = 平台生成，只在返回里给一次明文）。 */
@Data
public class MiddlewareUserCreateRequestDTO {

    private String instanceId;

    private String clusterId;

    private String namespace;

    /** 实例名（CR/Secret 名都从它推导） */
    private String name;

    private String username;

    private String password;

    /** 预设角色：只读 / 读写 / 管理员（各家支持的不一样，由适配器声明） */
    private String role;

    /** 管理员代管轨的租户 */
    private String tenantId;
}
