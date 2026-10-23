package com.coding.platformapi.models;

import lombok.Data;

/** {@code POST /middleware/instance/rename} 的请求体（只改显示名，不碰集群）。 */
@Data
public class MiddlewareRenameRequest {

    private String instanceId;

    private String displayName;

    /** 仅管理员代管轨使用；租户轨以 token 为准 */
    private String tenantId;
}
