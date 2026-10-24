package com.coding.k8sserver.components;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/**
 * SeaweedFS admin 管理台的登录凭证 Secret（{@code platform-seaweedfs-admin}）。
 *
 * <p>为什么平台要生成：chart 从 4.46 起，admin 绑定非 loopback 地址（{@code 0.0.0.0}，健康探针需要）
 * 时**没有口令会直接退出**；而把口令写进 values 会让它以明文进入操作记录的 values 快照。
 * 所以口令在这里生成、只写进 Secret，values 里只用 {@code admin.secret.existingSecret} 引用。
 *
 * <p>约定的 key 是 {@code username} / {@code password}（与 OTel 的 ES 凭证同名，便于运维记）。
 * 已存在时**不轮换**：升级/重装沿用原口令，避免把正在用的会话踢掉。
 */
public final class SeaweedAdminSecretObjects {

    public static final String USERNAME_KEY = "username";
    public static final String PASSWORD_KEY = "password";
    /** 管理台默认账号（可在 Secret 里改） */
    public static final String DEFAULT_USERNAME = "admin";

    private static final SecureRandom RANDOM = new SecureRandom();

    private SeaweedAdminSecretObjects() {
    }

    /** 已存在的 Secret 是否已经有完整凭证（两个键都非空）→ 有就不覆盖 */
    public static boolean hasCredentials(Map<String, String> data) {
        return data != null
                && data.get(USERNAME_KEY) != null && !data.get(USERNAME_KEY).isBlank()
                && data.get(PASSWORD_KEY) != null && !data.get(PASSWORD_KEY).isBlank();
    }

    public static Secret build(String namespace, String name) {
        return build(namespace, name, DEFAULT_USERNAME, randomPassword());
    }

    public static Secret build(String namespace, String name, String username, String password) {
        return new SecretBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .addToLabels("platform.coding/managed", "true")
                .addToLabels("platform.coding/component", "seaweedfs")
                .endMetadata()
                .withType("Opaque")
                .addToStringData(USERNAME_KEY, username)
                .addToStringData(PASSWORD_KEY, password)
                .build();
    }

    /** 24 字节随机 → base64url（32 字符，无填充；够长且不含需要转义的字符） */
    static String randomPassword() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
