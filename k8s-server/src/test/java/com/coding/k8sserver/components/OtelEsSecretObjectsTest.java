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
    void itCopiesTheFixedUsernameAndPasswordKeys() {
        // 平台约定：来源 Secret（monitor）key 固定为 username / password
        Secret source = new SecretBuilder()
                .withData(Map.of("username", b64("elastic"), "password", b64("pw"))).build();

        assertThat(OtelEsSecretObjects.value(source, OtelEsSecretObjects.USERNAME_KEY))
                .isEqualTo("elastic");
        assertThat(OtelEsSecretObjects.value(source, OtelEsSecretObjects.PASSWORD_KEY))
                .isEqualTo("pw");

        Secret target = OtelEsSecretObjects.build("elastic", "pw",
                "monitor", "platform-otel-es");

        assertThat(target.getStringData())
                .containsEntry("username", "elastic")
                .containsEntry("password", "pw");
        assertThat(target.getMetadata().getName()).isEqualTo("platform-otel-es");
    }

    @Test
    void aMissingKeyYieldsNullSoTheCallerCanFailLoudly() {
        Secret source = new SecretBuilder().withData(Map.of("username", b64("elastic"))).build();

        assertThat(OtelEsSecretObjects.value(source, OtelEsSecretObjects.PASSWORD_KEY)).isNull();
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
