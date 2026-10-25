package com.coding.platformapi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 开/关桶的匿名读（仅管理员）。
 *
 * <p>只给 {@code Read}、不给列目录：知道完整对象路径就能下载，但列不出桶里的文件。
 * 保存后 S3 网关会滚动一次（配置只在启动时读），秒级。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectStorageBucketAnonymous {
    private String bucketId;
    private String tenantId;
    private Boolean anonymousRead;
}
