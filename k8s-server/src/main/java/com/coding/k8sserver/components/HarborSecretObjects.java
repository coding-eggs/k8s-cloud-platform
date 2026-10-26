package com.coding.k8sserver.components;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;

import java.security.SecureRandom;
import java.util.Map;

/**
 * Harbor 的管理口令与加密 secretKey（{@code platform-harbor-secrets}）。
 *
 * <p>为什么平台生成：{@code harborAdminPassword} 写进 values 会以明文进入操作记录的
 * values 快照；{@code secretKey} 还用于加密 Harbor 库里的字段。
 *
 * <p>⚠️ 只能在**首次安装**时生成：Harbor 只在初始化时把口令写库，
 * {@code secretKey} 变了会让库里已加密的字段失效。调用方必须先确认 {@code harbor-core}
 * 不存在（见 {@code ComponentInstallController.harborSecrets}）。
 */
public final class HarborSecretObjects {

    public static final String ADMIN_PASSWORD_KEY = "HARBOR_ADMIN_PASSWORD";
    public static final String SECRET_KEY = "secretKey";

    private static final SecureRandom RANDOM = new SecureRandom();

    private HarborSecretObjects() {
    }

    /** 两个键都存在且非空白 → 视为已初始化，不覆盖 */
    public static boolean hasAllKeys(Map<String, String> data) {
        return data != null
                && data.get(ADMIN_PASSWORD_KEY) != null && !data.get(ADMIN_PASSWORD_KEY).isBlank()
                && data.get(SECRET_KEY) != null && !data.get(SECRET_KEY).isBlank();
    }

    public static Secret build(String namespace, String name) {
        return new SecretBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .addToLabels("platform.coding/managed", "true")
                .addToLabels("platform.coding/component", "harbor")
                .endMetadata()
                .withType("Opaque")
                .addToStringData(ADMIN_PASSWORD_KEY, randomPassword())
                .addToStringData(SECRET_KEY, randomSecretKey())
                .build();
    }

    /** 24 字节随机 → base64url（32 字符） */
    static String randomPassword() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Harbor 硬要求 16 字符：12 字节随机 → 16 个 base64 字符 */
    public static String randomSecretKey() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
