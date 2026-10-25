package com.coding.common.models.objectstorage;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 删除单个对象。
 *
 * <p>v1 只删**一个 key**：S3 没有目录，按前缀批量删一次可能删掉一整片（误点代价太大）。
 * 要清空目录得单独设计（列举 + 二次确认 + 条数上限）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectDeleteRequestDTO {
    private String clusterId;
    private String endpoint;
    private String bucket;
    private String key;
}
