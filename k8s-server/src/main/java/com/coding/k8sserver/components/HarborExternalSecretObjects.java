package com.coding.k8sserver.components;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;

/**
 * Harbor 外部 PostgreSQL / Redis 的口令 Secret。
 *
 * <p>Harbor chart 期望的键：
 * <ul>
 *   <li>数据库：{@code existingSecret} 的键 **password**（映射到 {@code POSTGRESQL_PASSWORD}）；</li>
 *   <li>Redis：键 **REDIS_PASSWORD**，ACL 模式下再加 **REDIS_USERNAME**
 *       —— chart 的 helper 用 helm {@code lookup} 读它，所以 Secret 必须在 helm 之前存在。</li>
 * </ul>
 */
public final class HarborExternalSecretObjects {

    public static final String DB_PASSWORD_KEY = "password";
    public static final String REDIS_PASSWORD_KEY = "REDIS_PASSWORD";
    public static final String REDIS_USERNAME_KEY = "REDIS_USERNAME";

    private HarborExternalSecretObjects() {
    }

    public static Secret buildDatabase(String namespace, String name, String password) {
        return base(namespace, name)
                .addToStringData(DB_PASSWORD_KEY, password)
                .build();
    }

    public static Secret buildRedis(String namespace, String name, String username, String password) {
        SecretBuilder builder = base(namespace, name)
                .addToStringData(REDIS_PASSWORD_KEY, password);
        if (username != null && !username.isBlank()) {
            builder.addToStringData(REDIS_USERNAME_KEY, username);
        }
        return builder.build();
    }

    /**
     * Secret 里是否有可用的某个键。
     *
     * <p>集群里读回来的 Secret 用 {@code data}（base64），本地用 builder 造的是 {@code stringData}
     * （write-only，API server 才会合进 data）—— 两边都要看。
     */
    public static boolean hasValue(Secret secret, String key) {
        if (secret == null) {
            return false;
        }
        java.util.Map<String, String> data = new java.util.HashMap<>();
        if (secret.getData() != null) {
            data.putAll(secret.getData());
        }
        if (secret.getStringData() != null) {
            data.putAll(secret.getStringData());
        }
        return data.get(key) != null && !data.get(key).isBlank();
    }

    private static SecretBuilder base(String namespace, String name) {
        return new SecretBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .addToLabels("platform.coding/managed", "true")
                .addToLabels("platform.coding/component", "harbor")
                .endMetadata()
                .withType("Opaque");
    }
}
