package com.coding.common.models.objectstorage;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 对象列表一页：items + 下一页游标（null = 已到末尾）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectListResultDTO {
    private java.util.List<ObjectListItemDTO> items;
    private String nextToken;
}
