package com.coding.k8sserver.middleware;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 平台建的账号存在哪：Secret {@code <实例名>-users}。
 *
 * <p>布局：key = 用户名、value = 口令；角色放 {@code role.<用户名>}（同一 Secret，不落库）。
 *
 * <p>只覆盖"平台建的账号"；内置账号（MySQL root / ES elastic / Milvus root…）的口令在
 * 各家 operator 自己的 Secret 里，由适配器负责写回，**不进这里** —— 否则
 * "平台建的账号 = 这个 Secret 里有 key"的识别规则会把内置账号也算进去。
 */
final class UsersSecret {

    /** 角色键前缀：口令读取要跳过它们 */
    static final String ROLE_PREFIX = "role.";

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
            if (k.startsWith(ROLE_PREFIX) || v == null || v.isBlank()) {
                return;
            }
            out.put(k, decode(v));
        });
        return out;
    }

    /** 读角色（用户名 → 角色）；没记过的账号不出现在结果里。 */
    static Map<String, String> roles(Secret secret) {
        Map<String, String> out = new LinkedHashMap<>();
        if (secret == null || secret.getData() == null) {
            return out;
        }
        secret.getData().forEach((k, v) -> {
            if (k.startsWith(ROLE_PREFIX) && v != null && !v.isBlank()) {
                out.put(k.substring(ROLE_PREFIX.length()), decode(v));
            }
        });
        return out;
    }

    /** 只带口令（角色留空）—— create 不带角色时用。 */
    static Map<String, String> merge(Map<String, String> base64Data, String username, String password) {
        return mergePassword(base64Data, username, password);
    }

    /** 新建账号：口令 + 角色一起写。 */
    static Map<String, String> merge(Map<String, String> base64Data, String username,
                                     String password, String role) {
        Map<String, String> out = mergePassword(base64Data, username, password);
        if (role != null && !role.isBlank()) {
            out.put(ROLE_PREFIX + username, encode(role));
        }
        return out;
    }

    /** 只改口令（角色保持原样）。 */
    static Map<String, String> mergePassword(Map<String, String> base64Data, String username, String password) {
        Map<String, String> out = new LinkedHashMap<>(base64Data == null ? Map.of() : base64Data);
        out.put(username, encode(password));
        return out;
    }

    /** 去掉一个账号（口令 + 角色两条）。 */
    static Map<String, String> without(Map<String, String> base64Data, String username) {
        Map<String, String> out = new LinkedHashMap<>(base64Data == null ? Map.of() : base64Data);
        out.remove(username);
        out.remove(ROLE_PREFIX + username);
        return out;
    }

    static Secret read(KubernetesClient client, String namespace, String instanceName) {
        return client.secrets().inNamespace(namespace).withName(name(instanceName)).get();
    }

    /** 写入/覆盖一个账号（口令 + 角色），保留其它账号。 */
    static void put(KubernetesClient client, String namespace, String instanceName,
                    String username, String password, String role) {
        Secret current = read(client, namespace, instanceName);
        write(client, namespace, instanceName,
                merge(current == null ? Map.of() : current.getData(), username, password, role));
    }

    /** 只更新口令（角色不动）。 */
    static void putPassword(KubernetesClient client, String namespace, String instanceName,
                            String username, String password) {
        Secret current = read(client, namespace, instanceName);
        write(client, namespace, instanceName,
                mergePassword(current == null ? Map.of() : current.getData(), username, password));
    }

    /** 删除一个账号；Secret 不存在或 key 不存在都静默。 */
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

    private static String decode(String base64) {
        return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
    }
}
