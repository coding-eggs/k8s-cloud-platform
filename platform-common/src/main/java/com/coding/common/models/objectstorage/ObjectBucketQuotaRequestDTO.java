package com.coding.common.models.objectstorage;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 设置桶容量上限（SeaweedFS 的 S3 扩展 {@code PUT /{bucket}?seaweedfs-quota}）。
 *
 * <p>{@code quotaBytes <= 0} = 清除配额。真正的强制执行在 SeaweedFS：
 * 超限后桶被标 readOnly，写入返回 403（删除仍允许）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectBucketQuotaRequestDTO {
    private String clusterId;
    private String endpoint;
    private String bucket;
    private Long quotaBytes;
}
