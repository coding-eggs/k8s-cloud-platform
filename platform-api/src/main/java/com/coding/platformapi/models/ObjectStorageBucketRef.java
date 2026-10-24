package com.coding.platformapi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 按台账 id 定位一个桶（删除 / 建凭据 / 浏览对象都走它）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectStorageBucketRef {
    private String clusterId;
    private String tenantId;
    private String bucketId;
}
