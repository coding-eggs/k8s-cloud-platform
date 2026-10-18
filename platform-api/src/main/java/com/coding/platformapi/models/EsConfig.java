package com.coding.platformapi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ES 日志参数（应用日志走 OTel logs → collector → ES）。
 *
 * <p>凭证是 Secret 引用；ECK 的 {@code es-es-elastic-user} 密码放在 key {@code elastic}，
 * 所以 {@code passwordKey} 默认也是 {@code elastic}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EsConfig {
    /** 完整 URL，含 scheme（默认 https://es-es-http.default.svc:9200） */
    private String endpoint;
    /** 用户名（ECK 默认 elastic） */
    private String username;
    /** 含密码的 Secret */
    private SecretRef credentialSecret;
    /** 密码所在的 key（ECK 默认 elastic） */
    private String passwordKey;
    /** 自签证书时跳过校验 */
    private boolean insecureSkipVerify;
    /** 日志索引前缀（默认 otel-logs；exporter 实际写成 otel-logs-<date>） */
    private String logsIndex;
}
