package com.coding.k8sserver.components;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;

/**
 * Tempo 的对象存储凭证 Secret。
 *
 * <p>Tempo 的 S3 配置在 chart values 里用 {@code ${MINIO_ACCESS_KEY}} / {@code ${MINIO_SECRET_KEY}}
 * 占位，运行时由 {@code -config.expand-env=true} 展开——所以 Secret 的 key 必须恰好是这两个。
 *
 * <p>来源 Secret 支持与 Thanos 相同的 key 组合（复用 {@link ThanosObjstoreObjects#findCredentials}）；
 * 凭证只在这里参与转换，不落日志、不出集群。
 */
public final class TempoObjstoreObjects {

    public static final String ACCESS_KEY = "MINIO_ACCESS_KEY";
    public static final String SECRET_KEY = "MINIO_SECRET_KEY";

    private TempoObjstoreObjects() {
    }

    public static Secret build(String accessKey, String secretKey,
                               String targetNamespace, String targetSecretName) {
        return new SecretBuilder()
                .withNewMetadata()
                .withName(targetSecretName)
                .withNamespace(targetNamespace)
                .addToLabels("platform.coding/managed", "true")
                .addToLabels("platform.coding/component", "otel-operator")
                .endMetadata()
                .withType("Opaque")
                .addToStringData(ACCESS_KEY, accessKey)
                .addToStringData(SECRET_KEY, secretKey)
                .build();
    }
}
