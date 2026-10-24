package com.coding.common.models.objectstorage;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 一个 S3 桶（SeaweedFS 的 ListBuckets 结果，仅用于对账/展示）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectBucketDTO {
    private String name;
    /** ISO-8601；部分实现可能为空 */
    private String creationDate;
}
