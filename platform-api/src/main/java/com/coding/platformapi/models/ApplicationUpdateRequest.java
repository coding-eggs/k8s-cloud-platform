package com.coding.platformapi.models;

import com.coding.common.models.app.AppSpecDTO;
import lombok.Data;

/** 编辑应用（改图/改名）；部署进行中会被拒绝。 */
@Data
public class ApplicationUpdateRequest {

    private String appId;

    /** 管理员代管轨显式指定租户；租户轨以 token 为准（以 effectiveTenantId 收口） */
    private String tenantId;

    private String name;

    private String displayName;

    private String description;

    private AppSpecDTO spec;
}
