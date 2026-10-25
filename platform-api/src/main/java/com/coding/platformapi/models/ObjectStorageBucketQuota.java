package com.coding.platformapi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 修改已有桶的容量上限（仅管理员）。
 *
 * <p>{@code quotaBytes} 为 null 或 &lt;= 0 表示**清除上限**（不限）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectStorageBucketQuota {
    private String bucketId;
    private String tenantId;
    private Long quotaBytes;
}
