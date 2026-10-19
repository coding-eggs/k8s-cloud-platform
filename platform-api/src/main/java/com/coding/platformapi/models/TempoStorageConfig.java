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
}
