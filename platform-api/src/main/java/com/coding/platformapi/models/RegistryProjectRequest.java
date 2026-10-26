package com.coding.platformapi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 租户镜像项目端点入参：list/sync 只用 clusterId；rebuild 还要 tenantId（空 = 平台共享）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegistryProjectRequest {
    private String clusterId;
    private String tenantId;
}
