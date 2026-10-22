package com.coding.platformapi.models;

import lombok.Data;

/** {@code POST /middleware/operator/install} 的请求体（管理员）。 */
@Data
public class MiddlewareOperatorInstallRequest {
    private String clusterId;
    private String code;
}
