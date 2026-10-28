package com.coding.k8sserver.components;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code kubernetes.io/tls} Secret 的构建（Harbor 的 HTTPS 证书用它）。
 *
 * <p>只做纯函数：校验 PEM 头、拼 Secret。写入由调用方 SSA。
 */
public final class TlsSecretObjects {

    private TlsSecretObjects() {
    }

    public static Secret build(String namespace, String name, String certPem, String keyPem) {
        String crt = normalize(certPem);
        String key = normalize(keyPem);
        if (!crt.contains("-----BEGIN CERTIFICATE-----")) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "证书内容不是 PEM（缺少 -----BEGIN CERTIFICATE-----）");
        }
        if (!key.contains("-----BEGIN") || !key.contains("PRIVATE KEY-----")) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "私钥内容不是 PEM（缺少 -----BEGIN ... PRIVATE KEY-----）");
        }
        Map<String, String> data = new LinkedHashMap<>();
        data.put("tls.crt", Base64.getEncoder().encodeToString(crt.getBytes(StandardCharsets.UTF_8)));
        data.put("tls.key", Base64.getEncoder().encodeToString(key.getBytes(StandardCharsets.UTF_8)));
        return new SecretBuilder()
                .withNewMetadata().withName(name).withNamespace(namespace)
                .addToLabels("platform.coding/managed", "true")
                .endMetadata()
                .withType("kubernetes.io/tls")
                .withData(data)
                .build();
    }

    /** 统一换行、保证结尾有换行（有些 nginx 解析对「最后一行没换行」不友好） */
    static String normalize(String pem) {
        String s = pem == null ? "" : pem.replace("\r\n", "\n").trim();
        return s.isEmpty() ? s : s + "\n";
    }
}
