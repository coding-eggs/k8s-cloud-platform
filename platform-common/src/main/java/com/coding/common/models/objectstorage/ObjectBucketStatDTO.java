package com.coding.common.models.objectstorage;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 桶的当前用量。
 *
 * <p>S3 **没有**「桶大小」这种 API，只能递归列举对象求和 —— 对象多时很贵，所以 k8s-server
 * 有列举上限：到顶就停下并置 {@code truncated=true}，页面显示「≥」而不是假装是精确值。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectBucketStatDTO {
    private String bucket;
    /** 已统计到的字节数（truncated=true 时是下限） */
    private long bytes;
    /** 已统计到的对象数（truncated=true 时是下限） */
    private long objectCount;
    /** 是否因为上限提前停止（页面用「≥」提示） */
    private boolean truncated;
}
