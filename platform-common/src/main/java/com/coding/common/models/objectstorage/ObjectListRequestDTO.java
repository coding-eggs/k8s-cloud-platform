package com.coding.common.models.objectstorage;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 列对象（S3 ListObjectsV2 语义）。
 *
 * <p>分页用 {@code after}（上一页最后一个 key）而不是 continuationToken：控制台按 key 字典序翻页，
 * 无状态、便于重试。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectListRequestDTO {
    private String clusterId;
    private String endpoint;
    private String bucket;
    /** 前缀（"目录"浏览用；以 / 结尾表示进目录） */
    private String prefix;
    /** 从哪个 key 之后开始（上一页最后一条） */
    private String after;
    /** 每页条数，服务端夹在 1..1000 */
    private Integer maxKeys;
}
