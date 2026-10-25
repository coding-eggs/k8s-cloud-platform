package com.coding.platformapi.models;

import lombok.Data;

/** 部署/升级：revision 为空 = 用当前 spec 生成新版本；非空 = 重放某个历史版本（回滚/重试）。 */
@Data
public class ApplicationDeployRequest {

    private String appId;

    /** 管理员代管轨显式指定租户；租户轨以 token 为准（以 effectiveTenantId 收口） */
    private String tenantId;

    private Integer revision;
}
