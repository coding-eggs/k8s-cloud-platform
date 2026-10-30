package com.coding.platformapi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

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

    /** PLATFORM 模式下平台写进 {@code monitor} 的凭证 Secret 名（外部地址模式不会用它） */
    public static final String PLATFORM_CREDENTIAL_SECRET = "platform-tempo-objstore";

    /** 平台拼出的真实桶名形状：{@code t-<8 位十六进制租户短码>-<逻辑名>} */
    private static final String PLATFORM_BUCKET_PATTERN = "t-[0-9a-fA-F]{8}-.+";

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
    /**
     * PLATFORM 专用：表单里填的**逻辑桶名**（如 {@code tempo}），与 {@link #bucket} 解析出的
     * 真实全名（{@code t-<租户短码>-<名字>}）分开存。
     *
     * <p>为什么要单独存：编辑表单回填的必须是逻辑名 —— 把真实全名再提交一次会被当成逻辑名，
     * 拼出 {@code t-xxx-t-xxx-tempo} 的新桶，Tempo 指向新桶 = 历史 trace 全"消失"。
     * 快照里记一份，EXTERNAL 模式为 null。
     */
    private String bucketName;
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

    /**
     * 从 {@code __platform.otel.tempoStorage} 快照判定存储来源。
     *
     * <p>2026-10-30 之前的快照**没有 source 字段**（{@code otelSnapshot} 漏写）：那种快照
     * 只能按两个特征反推 —— 凭证 Secret 是平台发的 {@link #PLATFORM_CREDENTIAL_SECRET}，
     * 且桶名是平台拼的形状。两个都满足才认定 PLATFORM，避免把手工填的外部桶误判成平台桶。
     */
    public static String sourceOf(Object snapshot) {
        if (!(snapshot instanceof Map<?, ?> map)) {
            return SOURCE_EXTERNAL;
        }
        String source = text(map.get("source"));
        if (source != null) {
            return source;
        }
        String bucket = text(map.get("bucket"));
        Object secret = map.get("credentialSecret");
        String secretName = secret instanceof Map<?, ?> s ? text(s.get("name")) : null;
        return PLATFORM_CREDENTIAL_SECRET.equals(secretName) && bucket != null
                && bucket.matches(PLATFORM_BUCKET_PATTERN) ? SOURCE_PLATFORM : SOURCE_EXTERNAL;
    }

    /**
     * 表单要回填的桶名：PLATFORM 是**逻辑名**，EXTERNAL 是原样的桶名。
     *
     * <p>优先用快照里单独记的 {@code bucketName}；旧快照没有时从真实全名里剥掉
     * {@code t-<短码>-} 前缀 —— 直接回填全名会在下次提交时被当成逻辑名再拼一次。
     */
    public static String logicalBucket(String source, Object bucketName, Object bucket) {
        String name = text(bucketName);
        if (name != null) {
            return name;
        }
        String full = text(bucket);
        if (SOURCE_PLATFORM.equals(source) && full != null && full.matches(PLATFORM_BUCKET_PATTERN)) {
            return full.substring(full.indexOf('-', 2) + 1);
        }
        return full;
    }

    private static String text(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }
}
