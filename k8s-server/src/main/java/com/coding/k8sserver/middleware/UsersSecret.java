package com.coding.k8sserver.middleware;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 平台建的账号存在哪：Secret {@code <实例名>-users}，key = 用户名、value = 口令。
 *
 * <p>只覆盖"平台建的账号"；内置账号的口令在各家 operator 自己的 Secret 里（如 MySQL
 * {@code <名称>-secrets}、ES {@code <名称>-es-elastic-user}），那是由适配器负责写回的。
 */
final class UsersSecret {

    private UsersSecret() {
    }

    static String name(String instanceName) {
        return instanceName + "-users";
    }

    /** 读全部非空口令（用户名 → 明文）；Secret 不存在返回空 map。 */
    static Map<String, String> passwords(Secret secret) {
        Map<String, String> out = new LinkedHashMap<>();
        if (secret == null || secret.getData() == null) {
            return out;
        }
        secret.getData().forEach((k, v) -> {
            if (v != null && !v.isBlank()) {
                out.put(k, new String(Base64.getDecoder().decode(v), StandardCharsets.UTF_8));
            }
        });
        return out;
    }

    /** 在既有 base64 数据上并入一个明文口令（同名覆盖）。 */
    static Map<String, String> merge(Map<String, String> base64Data, String username, String password) {
        Map<String, String> out = new LinkedHashMap<>(base64Data == null ? Map.of() : base64Data);
        out.put(username, encode(password));
        return out;
    }

    /** 去掉一个 key（其余保留）。 */
    static Map<String, String> without(Map<String, String> base64Data, String username) {
        Map<String, String> out = new LinkedHashMap<>(base64Data == null ? Map.of() : base64Data);
        out.remove(username);
        return out;
    }

    static Secret read(KubernetesClient client, String namespace, String instanceName) {
        return client.secrets().inNamespace(namespace).withName(name(instanceName)).get();
    }

    /** 写入/覆盖一个账号的口令（保留其它账号）。 */
    static void put(KubernetesClient client, String namespace, String instanceName,
                    String username, String password) {
        Secret current = read(client, namespace, instanceName);
        write(client, namespace, instanceName,
                merge(current == null ? Map.of() : current.getData(), username, password));
    }

    /** 删除一个账号的口令；Secret 不存在或 key 不存在都静默。 */
    static void remove(KubernetesClient client, String namespace, String instanceName, String username) {
        Secret secret = read(client, namespace, instanceName);
        if (secret == null) {
            return;
        }
        Map<String, String> left = without(secret.getData(), username);
        if (left.isEmpty()) {
            client.secrets().inNamespace(namespace).withName(name(instanceName)).delete();
            return;
        }
        write(client, namespace, instanceName, left);
    }

    private static void write(KubernetesClient client, String namespace, String instanceName,
                              Map<String, String> data) {
        Secret secret = new SecretBuilder()
                .withNewMetadata()
                .withName(name(instanceName))
                .withNamespace(namespace)
                .addToLabels("platform.coding/managed-by", "middleware")
                .endMetadata()
                .withData(data)
                .build();
        client.secrets().inNamespace(namespace).resource(secret).createOrReplace();
    }

    private static String encode(String plain) {
        return Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }
}
