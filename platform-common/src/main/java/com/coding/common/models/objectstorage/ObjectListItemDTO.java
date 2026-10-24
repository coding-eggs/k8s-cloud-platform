package com.coding.common.models.objectstorage;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 对象列表里的一行。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectListItemDTO {
    private String key;
    private long size;
    /** ISO-8601 */
    private String lastModified;
    private String etag;
}
