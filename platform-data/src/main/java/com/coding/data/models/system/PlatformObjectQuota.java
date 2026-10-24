package com.coding.data.models.system;

import lombok.Data;

import java.util.Date;

/** 对象存储按租户的桶配额（表 platform_object_quota）。没有记录 = 用平台默认值。 */
@Data
public class PlatformObjectQuota {

    private String clusterId;

    private String tenantId;

    /** 该租户在本集群可拥有的桶数上限 */
    private Integer maxBuckets;

    private String updatedBy;

    private Date updatedAt;
}
