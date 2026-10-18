package com.coding.k8sserver.components;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OtelEsSecretObjectsTest {

    @Test
    void itNormalizesTheEckPasswordUnderAStableKey() {
        Secret source = new SecretBuilder().withData(Map.of("elastic", b64("pw"))).build();

        assertThat(OtelEsSecretObjects.password(source, "elastic")).isEqualTo("pw");

        Secret target = OtelEsSecretObjects.build("elastic", "pw",
                "monitor", "platform-otel-es");

        assertThat(target.getStringData())
                .containsEntry("username", "elastic")
                .containsEntry("password", "pw");
        assertThat(target.getMetadata().getName()).isEqualTo("platform-otel-es");
    }

    @Test
    void aMissingKeyYieldsNullSoTheCallerCanFailLoudly() {
        Secret source = new SecretBuilder().withData(Map.of("elastic", b64("pw"))).build();

        assertThat(OtelEsSecretObjects.password(source, "password")).isNull();
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
