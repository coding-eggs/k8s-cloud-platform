package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * ES 凭证 Secret 的下发参数（OTel 批次一）。
 *
 * <p>collector 的 elasticsearch exporter 用 basic auth 写 ES；密码从集群里已有的 Secret
 * （ECK 默认是 {@code es-es-elastic-user}，key 为 {@code elastic}）读出，
 * 归一化写入 {@code monitor/platform-otel-es}（key 为 {@code username}/{@code password}）。
 */
@Data
public class OtelEsSecretRequestDTO {
    private String clusterId;
    /** 来源 Secret 的命名空间（ECK 默认 default） */
    private String namespace;
    /** 来源 Secret 名（ECK 默认 es-es-elastic-user） */
    private String secretName;
    /** 密码在来源 Secret 里的 key（ECK 默认 elastic） */
    private String passwordKey;
    /** ES 用户名（ECK 默认 elastic） */
    private String username;
    /** 目标 Secret 的命名空间（collector 在 monitor，必须同 ns） */
    private String targetNamespace;
    /** 目标 Secret 名（collector 的 env 指向它） */
    private String targetSecretName;
}
