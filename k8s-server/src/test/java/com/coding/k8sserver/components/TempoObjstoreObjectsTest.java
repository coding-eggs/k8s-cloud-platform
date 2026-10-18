package com.coding.k8sserver.components;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TempoObjstoreObjectsTest {

    @Test
    void itCopiesOnlyTheTwoCredentialsUnderStableKeys() {
        Secret source = new SecretBuilder().withData(Map.of(
                "access-key", b64("minio"),
                "secret-key", b64("minio-secret"))).build();
        String[] credentials = ThanosObjstoreObjects.findCredentials(source.getData());

        Secret target = TempoObjstoreObjects.build(credentials[0], credentials[1],
                "monitor", "platform-tempo-objstore");

        assertThat(target.getStringData())
                .containsEntry("MINIO_ACCESS_KEY", "minio")
                .containsEntry("MINIO_SECRET_KEY", "minio-secret")
                .doesNotContainKeys("access-key", "secret-key");
        assertThat(target.getMetadata().getName()).isEqualTo("platform-tempo-objstore");
        assertThat(target.getMetadata().getNamespace()).isEqualTo("monitor");
        assertThat(target.getMetadata().getLabels())
                .containsEntry("platform.coding/component", "otel-operator");
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
