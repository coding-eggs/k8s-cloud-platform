package com.coding.k8sserver.components;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Thanos 对象存储配置的拼装：凭证识别只认已知 key 组合，拼出来的 YAML 必须是官方 schema。
 */
class ThanosObjstoreObjectsTest {

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void findsCredentialsAmongKnownKeyPairs() {
        String[] creds = ThanosObjstoreObjects.findCredentials(
                Map.of("access-key", b64("AK"), "secret-key", b64("SK")));

        assertThat(creds).containsExactly("AK", "SK");
    }

    @Test
    void supportsMinioAndAwsStyleKeys() {
        assertThat(ThanosObjstoreObjects.findCredentials(
                Map.of("MINIO_ACCESS_KEY", b64("a"), "MINIO_SECRET_KEY", b64("b"))))
                .containsExactly("a", "b");
        assertThat(ThanosObjstoreObjects.findCredentials(
                Map.of("AWS_ACCESS_KEY_ID", b64("a"), "AWS_SECRET_ACCESS_KEY", b64("b"))))
                .containsExactly("a", "b");
    }

    @Test
    void returnsNullWhenNoPairMatches() {
        assertThat(ThanosObjstoreObjects.findCredentials(Map.of("user", b64("x"), "pass", b64("y"))))
                .isNull();
    }

    @Test
    void yamlNormalizesTheSchemeAndWritesTheSkipVerifyBlock() {
        String yaml = ThanosObjstoreObjects.objstoreYaml(
                "http://minio.default.svc:9000/", "thanos", "AK", "SK", true);

        assertThat(yaml)
                .contains("type: S3")
                .contains("bucket: \"thanos\"")
                .contains("endpoint: \"minio.default.svc:9000\"")
                .contains("access_key: \"AK\"")
                .contains("secret_key: \"SK\"")
                .contains("insecure: true")
                .contains("insecure_skip_verify: true")
                .contains("tls_config:");
    }

    @Test
    void anHttpsEndpointStaysTlsAndHasNoSkipVerifyBlockByDefault() {
        String yaml = ThanosObjstoreObjects.objstoreYaml(
                "https://minio.example.com", "thanos", "AK", "SK", false);

        assertThat(yaml).contains("endpoint: \"minio.example.com\"")
                .contains("insecure: false")
                .doesNotContain("insecure_skip_verify");
    }

    @Test
    void valuesAreQuotedSoOddCharactersCannotBreakTheYaml() {
        String yaml = ThanosObjstoreObjects.objstoreYaml(
                "minio:9000", "bucket\"x", "a\\b", "sk", false);

        assertThat(yaml).contains("bucket: \"bucket\\\"x\"")
                .contains("access_key: \"a\\\\b\"");
    }
}
