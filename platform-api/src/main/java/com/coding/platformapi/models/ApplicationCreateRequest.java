package com.coding.platformapi.models;

import lombok.Data;

/** 创建应用：只建台账；服务与依赖创建后在应用页添加。 */
@Data
public class ApplicationCreateRequest {

    private String clusterId;

    private String namespace;

    private String name;

    private String displayName;

    private String description;

    /** 管理员代管轨显式指定租户；租户轨以 token 为准。 */
    private String tenantId;
}
