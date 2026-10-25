package com.coding.platformapi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 列对象：桶 + 前缀 + 游标分页。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectStorageObjectQuery {
    private String clusterId;
    private String tenantId;
    private String bucketId;
    private String prefix;
    /** 上一页最后一个 key */
    private String after;
    private Integer maxKeys;
    /** 分组分隔符（"/" = 按目录下钻；空 = 扁平递归） */
    private String delimiter;
}
