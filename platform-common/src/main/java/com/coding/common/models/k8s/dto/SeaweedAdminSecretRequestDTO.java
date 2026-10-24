package com.coding.common.models.k8s.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SeaweedFS admin 管理台的凭证 Secret（S3.6 组件安装用）。
 *
 * <p>口令由 k8s-server 在集群内生成并只写进 Secret，**不经过 platform-api**，
 * 也不会进操作记录的 values 快照。已存在（含 {@code username} / {@code password} 两个键）时原样保留，
 * 升级不轮换。
 */
@Data
@NoArgsConstructor
public class SeaweedAdminSecretRequestDTO {
    private String clusterId;
    /** Secret 所在命名空间（= SeaweedFS 的 release 命名空间） */
    private String namespace;
    /** Secret 名（平台固定 {@code platform-seaweedfs-admin}） */
    private String secretName;
}
