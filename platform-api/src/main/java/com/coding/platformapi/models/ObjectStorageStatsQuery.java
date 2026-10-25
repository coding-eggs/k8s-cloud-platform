package com.coding.platformapi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 批量查桶的当前用量（页面一次要显示一屏桶的大小）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectStorageStatsQuery {
    private String clusterId;
    private String tenantId;
    private List<String> buckets;
}
