package com.coding.platformapi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上传单个对象。
 *
 * <p>{@code size} 是浏览器给的 {@code File.size}（不是从流里数出来的）：两端都要用它
 * —— 平台侧据此拒超限，k8s-server 侧 MinIO 的 putObject 需要对象长度。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectStorageObjectUpload {
    private String bucketId;
    private String key;
    private Long size;
    private String contentType;
}
