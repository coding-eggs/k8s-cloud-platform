package com.coding.common.models.objectstorage;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对象列表一页：items + 子目录 + 下一页游标（null = 已到末尾）。
 *
 * <p>{@code folders} 是 S3 的 CommonPrefixes（以分隔符结尾的「子目录」）：控制台按文件浏览器逐层下钻，
 * 只把当前层的文件放 items、下一层目录放 folders，而不是把整棵树拍平。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectListResultDTO {
    private java.util.List<ObjectListItemDTO> items;
    private java.util.List<String> folders;
    private String nextToken;
}
