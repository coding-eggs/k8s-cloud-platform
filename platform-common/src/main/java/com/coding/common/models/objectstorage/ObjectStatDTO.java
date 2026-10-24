package com.coding.common.models.objectstorage;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 对象元数据（HeadObject 的窄投影）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectStatDTO {
    private String key;
    private long size;
    private String etag;
    private String contentType;
    private String lastModified;
}
