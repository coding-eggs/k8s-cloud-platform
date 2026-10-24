package com.coding.platformapi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 某租户的桶配额与当前用量。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectStorageQuotaView {
    private String tenantId;
    private int used;
    private int maxBuckets;
    private String updatedBy;
    private java.util.Date updatedAt;
}
