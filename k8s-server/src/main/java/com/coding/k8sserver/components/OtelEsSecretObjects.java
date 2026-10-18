package com.coding.k8sserver.components;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * collector 写 ES 用的 basic auth Secret。
 *
 * <p>ECK 把用户密码放在 {@code es-es-elastic-user} 的 {@code elastic} key 里；collector 侧的 env
 * 固定读 {@code platform-otel-es} 的 {@code username}/{@code password}，所以这里做一次归一化。
 * 密码只在集群内转换，不经过 platform-api。
 */
public final class OtelEsSecretObjects {

    public static final String USERNAME_KEY = "username";
    public static final String PASSWORD_KEY = "password";

    private OtelEsSecretObjects() {
    }

    /** 解码来源 Secret 里的密码；key 不存在返回 null */
    public static String password(Secret source, String key) {
        if (source == null || source.getData() == null || key == null) {
            return null;
        }
        String encoded = source.getData().get(key);
        if (encoded == null) {
            return null;
        }
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    public static Secret build(String username, String password,
                               String targetNamespace, String targetSecretName) {
        return new SecretBuilder()
                .withNewMetadata()
                .withName(targetSecretName)
                .withNamespace(targetNamespace)
                .addToLabels("platform.coding/managed", "true")
                .addToLabels("platform.coding/component", "otel-operator")
                .endMetadata()
                .withType("Opaque")
                .addToStringData(USERNAME_KEY, username)
                .addToStringData(PASSWORD_KEY, password)
                .build();
    }
}
