package com.coding.common.models.objectstorage;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 开/关某个桶的**匿名读**。
 *
 * <p>机制：SeaweedFS 的 S3 配置里有一条没有 credentials 的 {@code anonymous} 身份，
 * 不带签名的请求按它的 actions 判定。这里改的就是它 —— 只加/删 {@code Read:<bucket>}，
 * 其它桶与其它身份都不受影响。改完必须滚动 S3 网关（配置只在启动时读）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectBucketAnonymousRequestDTO {
    private String clusterId;
    private String bucket;
    private Boolean anonymousRead;
}
