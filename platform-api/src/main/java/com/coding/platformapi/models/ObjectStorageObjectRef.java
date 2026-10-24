package com.coding.platformapi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 定位一个对象（详情 / 下载）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectStorageObjectRef {
    private String clusterId;
    private String tenantId;
    private String bucketId;
    private String key;
}
