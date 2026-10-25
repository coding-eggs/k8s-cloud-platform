package com.coding.platformapi.models;

import com.coding.common.models.app.AppSpecDTO;
import lombok.Data;

/** 创建应用：只落 spec，不部署。 */
@Data
public class ApplicationCreateRequest {

    private String clusterId;

    private String namespace;

    private String name;

    private String displayName;

    private String description;

    private AppSpecDTO spec;

    /** 管理员代管轨显式指定租户；租户轨以 token 为准。 */
    private String tenantId;
}
