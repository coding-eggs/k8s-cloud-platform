package com.coding.platformapi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tempo 的 MinIO 存储参数（管理集群）。
 *
 * <p>地址、桶、TLS 全是表单字段；凭证是 Secret 引用，平台在集群内把它转成
 * {@code platform-tempo-objstore}（key 为 {@code MINIO_ACCESS_KEY} / {@code MINIO_SECRET_KEY}）。
 *
 * <p>⚠️ Tempo 用**独立桶**，不与 Thanos 共用；不设前缀，block 直接写在桶根。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TempoStorageConfig {

    /** 用平台对象存储（默认）：地址、桶、凭据全部由平台解析/发放 */
    public static final String SOURCE_PLATFORM = "PLATFORM";
    /** 用外部地址：endpoint / 桶 / 凭证 Secret 由表单手填 */
    public static final String SOURCE_EXTERNAL = "EXTERNAL";

    /**
     * 存储来源（PLATFORM / EXTERNAL，空 = PLATFORM）。
     *
     * <p>PLATFORM 下 {@link #bucket} 是**逻辑名**（默认 {@code tempo}），平台按
     * {@code t-<租户短码>-<名字>} 解析出真实桶、必要时现建；{@link #credentialSecret} 由平台写。
     * EXTERNAL 下三个字段都是原样使用。
     */
    private String source;

    /** PLATFORM 专用：桶归属租户 id（空 = 复用同名桶的归属，否则落到 default 租户） */
    private String tenantId;

    /** MinIO 地址（host:port） */
    private String endpoint;
    /** 桶名，默认 tempo（与 Thanos 的 thanos 桶分开） */
    private String bucket;
    /** 含 access key / secret key 的 Secret */
    private SecretRef credentialSecret;
    /** 自签证书 / HTTPS 时跳过证书校验 */
    private boolean insecureSkipVerify;
    /**
     * 明文 HTTP（表单的「传输方式」选择）。
     * null = 按 endpoint 的 scheme 推断（兼容不带该字段的旧调用/快照）；表单总会显式下发。
     */
    private Boolean plaintext;

    /**
     * 兼容旧调用（只有外部地址那一套字段）：{@code source} 固定为 {@link #SOURCE_EXTERNAL}。
     *
     * <p>不带 source 的 5 参数构造在语义上只可能是"外部地址"，指成 PLATFORM 会把
     * endpoint/bucket 的含义悄悄换掉。
     */
    public TempoStorageConfig(String endpoint, String bucket, SecretRef credentialSecret,
                              boolean insecureSkipVerify, Boolean plaintext) {
        this.source = SOURCE_EXTERNAL;
        this.endpoint = endpoint;
        this.bucket = bucket;
        this.credentialSecret = credentialSecret;
        this.insecureSkipVerify = insecureSkipVerify;
        this.plaintext = plaintext;
    }
}
