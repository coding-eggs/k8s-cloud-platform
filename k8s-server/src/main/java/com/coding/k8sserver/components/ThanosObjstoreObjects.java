package com.coding.k8sserver.components;

import java.util.List;
import java.util.Map;

/**
 * Thanos 对象存储配置（S3.6）。
 *
 * <p>平台只支持 MinIO/S3（用户 2026-10-17 裁定）：从已有凭证 Secret 里读出
 * access key / secret key，与表单的 endpoint / bucket 一起拼成官方要求的 {@code objstore.yml}。
 *
 * <p>⚠️ 凭证只在这里参与拼装，不落日志、不出集群。
 */
public final class ThanosObjstoreObjects {

    /** Thanos 读取的对象存储配置文件名（chart 的 global.objstore.secretKey） */
    public static final String OBJSTORE_KEY = "objstore.yml";

    /**
     * 可识别的凭证 key 组合，按顺序尝试。
     * 平台不强制一种命名，但找不到任何一组时会明确报错并列出支持的名字。
     */
    private static final List<String[]> CREDENTIAL_KEYS = List.of(
            new String[]{"access-key", "secret-key"},
            new String[]{"accessKey", "secretKey"},
            new String[]{"ACCESS_KEY", "SECRET_KEY"},
            new String[]{"MINIO_ACCESS_KEY", "MINIO_SECRET_KEY"},
            new String[]{"minio-access-key", "minio-secret-key"},
            new String[]{"AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY"});

    private ThanosObjstoreObjects() {
    }

    /**
     * 从 Secret 的 data（key → base64 值）里找一组凭证。
     * @return {@code [accessKey, secretKey]}（已 base64 解码），找不到返回 null
     */
    public static String[] findCredentials(Map<String, String> data) {
        if (data == null) {
            return null;
        }
        for (String[] pair : CREDENTIAL_KEYS) {
            String access = data.get(pair[0]);
            String secret = data.get(pair[1]);
            if (access != null && secret != null) {
                return new String[]{decode(access), decode(secret)};
            }
        }
        return null;
    }

    /** 支持的凭证 key 组合（报错文案用） */
    public static String supportedKeyPairs() {
        StringBuilder sb = new StringBuilder();
        for (String[] pair : CREDENTIAL_KEYS) {
            if (!sb.isEmpty()) {
                sb.append(" / ");
            }
            sb.append(pair[0]).append('+').append(pair[1]);
        }
        return sb.toString();
    }

    /**
     * 拼 {@code objstore.yml}。
     *
     * <p>endpoint 归一化：带 {@code http://} 前缀时去掉并置 {@code insecure: true}（明文连接），
     * 否则按 HTTPS 处理。{@code insecure_skip_verify} 同时写 http_config 与 tls_config
     * （用户要求的形式，两个都写兼容性最好）。
     */
    public static String objstoreYaml(String endpoint, String bucket,
                                      String accessKey, String secretKey, boolean insecureSkipVerify) {
        String normalized = endpoint == null ? "" : endpoint.trim();
        boolean plaintext = normalized.startsWith("http://");
        if (plaintext) {
            normalized = normalized.substring("http://".length());
        } else if (normalized.startsWith("https://")) {
            normalized = normalized.substring("https://".length());
        }
        normalized = normalized.replaceAll("/+$", ""); // 去掉尾斜杠

        StringBuilder yaml = new StringBuilder();
        yaml.append("type: S3\n");
        yaml.append("config:\n");
        yaml.append("  bucket: ").append(quote(bucket)).append('\n');
        yaml.append("  endpoint: ").append(quote(normalized)).append('\n');
        yaml.append("  access_key: ").append(quote(accessKey)).append('\n');
        yaml.append("  secret_key: ").append(quote(secretKey)).append('\n');
        yaml.append("  insecure: ").append(plaintext).append('\n');
        if (insecureSkipVerify) {
            yaml.append("  http_config:\n");
            yaml.append("    insecure_skip_verify: true\n");
            yaml.append("    tls_config:\n");
            yaml.append("      insecure_skip_verify: true\n");
        }
        return yaml.toString();
    }

    private static String quote(String value) {
        String v = value == null ? "" : value;
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String decode(String base64) {
        return new String(java.util.Base64.getDecoder().decode(base64), java.nio.charset.StandardCharsets.UTF_8);
    }
}
