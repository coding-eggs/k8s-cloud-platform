package com.coding.common.models.k8s.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Harbor 管理口令与 secretKey 的 Secret（S3.6 组件安装用）。
 *
 * <p>口令由 k8s-server 在集群内生成、只写进 Secret；platform-api 只在真正调 Harbor API
 * 时读取它。已存在（两个键都在）时原样保留，升级不轮换。
 */
@Data
@NoArgsConstructor
public class HarborSecretsRequestDTO {
    private String clusterId;
    /** Secret 所在命名空间（= Harbor 的 release 命名空间） */
    private String namespace;
    /** Secret 名（平台固定 platform-harbor-secrets） */
    private String secretName;
}
