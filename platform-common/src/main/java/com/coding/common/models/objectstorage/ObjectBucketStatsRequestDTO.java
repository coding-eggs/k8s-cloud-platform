package com.coding.common.models.objectstorage;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量查桶的当前用量。
 *
 * <p>为什么是"批量"：页面一次要显示一屏桶的大小，逐桶一次 HTTP 会让列表卡住；
 * 而且 S3 没有"桶大小"API，每个桶都得列举求和（见 {@link ObjectBucketStatDTO}）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectBucketStatsRequestDTO {
    private String clusterId;
    private String endpoint;
    /** 要统计的桶名（调用方只传自己有权限看到的桶） */
    private List<String> buckets;
}
