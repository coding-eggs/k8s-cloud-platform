package com.coding.k8sserver.components;

import com.coding.common.exception.CloudPlatformException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TlsSecretObjectsTest {

    private static final String CRT = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----";
    private static final String KEY = "-----BEGIN RSA PRIVATE KEY-----\nMIIE\n-----END RSA PRIVATE KEY-----";

    @Test
    void buildsAKubernetesTlsSecret() {
        var secret = TlsSecretObjects.build("istio-system", "harbor-tls", CRT, KEY);

        assertThat(secret.getType()).isEqualTo("kubernetes.io/tls");
        assertThat(secret.getMetadata().getNamespace()).isEqualTo("istio-system");
        assertThat(secret.getMetadata().getName()).isEqualTo("harbor-tls");
        String crt = new String(Base64.getDecoder().decode(secret.getData().get("tls.crt")),
                StandardCharsets.UTF_8);
        assertThat(crt).startsWith("-----BEGIN CERTIFICATE-----").endsWith("\n");
        assertThat(secret.getData()).containsKeys("tls.crt", "tls.key");
    }

    @Test
    void rejectsNonPemInput() {
        assertThatThrownBy(() -> TlsSecretObjects.build("ns", "s", "not a cert", KEY))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("BEGIN CERTIFICATE");
        assertThatThrownBy(() -> TlsSecretObjects.build("ns", "s", CRT, "not a key"))
                .hasMessageContaining("PRIVATE KEY");
    }

    @Test
    void normalizesCrlf() {
        assertThat(TlsSecretObjects.normalize("-----BEGIN CERTIFICATE-----\r\nabc\r\n-----END CERTIFICATE-----"))
                .doesNotContain("\r");
    }
}
