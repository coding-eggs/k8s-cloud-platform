package com.coding.platformapi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 设置某租户在本集群的桶数上限（仅管理员）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectStorageQuotaSet {
    private String clusterId;
    private String tenantId;
    private Integer maxBuckets;
}
