package com.coding.platformapi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tempo 的 MinIO 存储参数（管理集群）。
 *
 * <p>地址、桶、前缀、TLS 全是表单字段；凭证是 Secret 引用，平台在集群内把它转成
 * {@code platform-tempo-objstore}（key 为 {@code MINIO_ACCESS_KEY} / {@code MINIO_SECRET_KEY}）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TempoStorageConfig {
    /** MinIO 地址（host:port） */
    private String endpoint;
    /** 桶名，默认 thanos */
    private String bucket;
    /** 桶内前缀，默认 tempo（与 Thanos 数据隔离） */
    private String prefix;
    /** 含 access key / secret key 的 Secret */
    private SecretRef credentialSecret;
    /** 自签证书 / HTTP 时跳过校验 */
    private boolean insecureSkipVerify;
}
