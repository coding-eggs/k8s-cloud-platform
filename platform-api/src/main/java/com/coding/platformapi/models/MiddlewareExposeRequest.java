package com.coding.platformapi.models;

import lombok.Data;

/** {@code POST /middleware/instance/expose/update} 的请求体：一个端点改成 NONE / GATEWAY / NODEPORT，立即生效。 */
@Data
public class MiddlewareExposeRequest {

    private String instanceId;

    private String endpointKey;

    /** NONE / GATEWAY / NODEPORT */
    private String mode;

    /** 仅管理员代管轨使用；租户轨以 token 为准 */
    private String tenantId;
}
