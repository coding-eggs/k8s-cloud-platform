package com.coding.platformapi.models;

import lombok.Data;

/** {@code POST /middleware/instance/delete} 的请求体。 */
@Data
public class MiddlewareDeleteRequest {
    private String instanceId;
    /** true = 保留 PVC（数据不删） */
    private boolean keepPvc = true;
    private String tenantId;
}
