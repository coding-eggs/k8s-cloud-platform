package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * Tempo 对象存储凭证 Secret 的下发参数（OTel 批次一）。
 *
 * <p>与 Thanos 的 objstore 一样，凭证不经过 platform-api：这里只给“从哪个 Secret 读、写到哪个 Secret”，
 * 读值、转 key 都在 k8s-server 内完成。
 */
@Data
public class TempoObjstoreRequestDTO {
    private String clusterId;
    /** 来源凭证 Secret 的命名空间 */
    private String namespace;
    /** 来源凭证 Secret 名（含 access key / secret key，支持与 Thanos 相同的 key 组合） */
    private String secretName;
    /** 目标 Secret 的命名空间（必须与 Tempo 部署命名空间一致，默认 monitor） */
    private String targetNamespace;
    /** 目标 Secret 名（Tempo chart 的 extraEnvFrom 指向它） */
    private String targetSecretName;
}
