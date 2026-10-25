package com.coding.data.models.system;

import lombok.Data;

import java.util.Date;

/** 对象存储桶台账（表 platform_object_bucket）：一个桶 = 集群内一套 SeaweedFS 里的一个 S3 桶。 */
@Data
public class PlatformObjectBucket {

    private String id;

    private String clusterId;

    /** S3 桶名（平台生成 t-<租户短码>-<名字>） */
    private String bucket;

    /** 归属租户 */
    private String tenantId;

    private String displayName;

    /** ACTIVE / DELETING / DELETED */
    private String status;

    private String createdBy;

    private Date createdAt;

    private Date updatedAt;

    /** 软删时间；同名桶可以在删除后重建 */
    private Date deletedAt;

    /** 桶容量上限（字节）；null = 不限。真正的执行在 SeaweedFS（每桶 quota），这里只是声明值 */
    private Long quotaBytes;
}
