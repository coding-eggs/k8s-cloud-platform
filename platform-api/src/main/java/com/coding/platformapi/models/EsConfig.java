package com.coding.platformapi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ES 日志参数（应用日志走 OTel logs → collector → ES）。
 *
 * <p>凭证是**固定约定**的 Secret：必须放在 {@code monitor} 命名空间，且包含
 * {@code username} / {@code password} 两个 key。平台在集群内把它复制成
 * {@code monitor/platform-otel-es} 给 collector 用，密码不经过 platform-api。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EsConfig {
    /** 完整 URL，含 scheme（默认 https://es-es-http.default.svc:9200） */
    private String endpoint;
    /** monitor 命名空间下、含 username/password 的 Secret */
    private SecretRef credentialSecret;
    /** 自签证书时跳过校验 */
    private boolean insecureSkipVerify;
}
