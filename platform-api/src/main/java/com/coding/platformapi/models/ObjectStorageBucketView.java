package com.coding.platformapi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 桶列表项（台账 + 有效凭据数）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectStorageBucketView {
    private String id;
    private String clusterId;
    private String bucket;
    private String tenantId;
    private String displayName;
    private String status;
    private String createdBy;
    private java.util.Date createdAt;
    /** 当前有效凭据数 */
    private int credentialCount;
    /** 桶容量上限（字节）；null = 不限 */
    private Long quotaBytes;
    /** 是否允许匿名读（知道完整路径即可下载，不能列目录） */
    private boolean anonymousRead;
}
