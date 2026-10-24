package com.coding.common.models.objectstorage;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 单个对象的元数据查询（HeadObject）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectStatRequestDTO {
    private String clusterId;
    private String endpoint;
    private String bucket;
    private String key;
}
