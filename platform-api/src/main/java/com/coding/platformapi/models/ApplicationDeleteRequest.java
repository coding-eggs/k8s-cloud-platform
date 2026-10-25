package com.coding.platformapi.models;

import lombok.Data;

/** 删除应用：先把本应用台账里的集群对象删掉（PVC 保留），再软删台账。 */
@Data
public class ApplicationDeleteRequest {

    private String appId;

    /** 管理员代管轨显式指定租户；租户轨以 token 为准（以 effectiveTenantId 收口） */
    private String tenantId;
}
