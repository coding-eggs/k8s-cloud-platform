package com.coding.platformapi.models;

import lombok.Data;

/** {@code POST /middleware/form-options} 的请求体。 */
@Data
public class MiddlewareOptionsRequest {

    private String clusterId;

    /** 管理员代管轨显式指定的租户；租户轨以 token 为准 */
    private String tenantId;
}
