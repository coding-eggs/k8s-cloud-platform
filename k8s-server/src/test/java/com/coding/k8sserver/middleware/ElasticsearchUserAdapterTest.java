package com.coding.k8sserver.middleware;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Elasticsearch 的纯函数部分：内置角色映射 / 用户体 / 平台账号前缀。
 *
 * <p>ES 的用户名空间是全局的，平台建的账号统一加 {@code platform_} 前缀，列表再剥掉显示。
 */
class ElasticsearchUserAdapterTest {

    @Test
    void roleMapsToBuiltinEsRoles() {
        assertThat(ElasticsearchUserAdapter.esRoles("只读")).containsExactly("viewer");
        assertThat(ElasticsearchUserAdapter.esRoles("读写")).containsExactly("editor");
        assertThat(ElasticsearchUserAdapter.esRoles("管理员")).containsExactly("superuser");
    }

    @Test
    void userBodyCarriesPasswordAndRoles() {
        Map<String, Object> body = ElasticsearchUserAdapter.userBody("pw-123456", "只读");

        assertThat(body)
                .containsEntry("password", "pw-123456")
                .containsEntry("roles", java.util.List.of("viewer"));
    }

    @Test
    void onlyElasticAndPlatformPrefixedUsersAreShown() {
        assertThat(ElasticsearchUserAdapter.isShown("elastic")).isTrue();
        assertThat(ElasticsearchUserAdapter.isShown("platform_alice")).isTrue();
        assertThat(ElasticsearchUserAdapter.isShown("kibana_system")).isFalse();
        assertThat(ElasticsearchUserAdapter.isShown("logstash_system")).isFalse();
    }

    @Test
    void esUsernameAddsPrefixAndDisplayStripsIt() {
        assertThat(ElasticsearchUserAdapter.esUsername("alice")).isEqualTo("platform_alice");
        assertThat(ElasticsearchUserAdapter.displayName("platform_alice")).isEqualTo("alice");
        assertThat(ElasticsearchUserAdapter.displayName("elastic")).isEqualTo("elastic");
    }

    @Test
    void unknownRoleIsRejectedInsteadOfFallingBack() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> ElasticsearchUserAdapter.esRoles("admin"))
                .isInstanceOf(com.coding.common.exception.CloudPlatformException.class)
                .hasMessageContaining("不支持的角色");
    }

    // ---------- 到底走 http 还是 https：判据在 CR 上，不在 Secret 上 ----------

    /**
     * 2026-10-27 真集群实锤：实例 `http.tls.selfSignedCertificate.disabled: true` 时 ES 的
     * {@code xpack.security.http.ssl.enabled} 是 false（明文），但 ECK **照样**生成
     * {@code -es-http-certs-public} / {@code -es-http-ca-internal} 两个 Secret。
     * 拿"Secret 在不在"当 TLS 判据 → 拿 https 去连明文端口
     * （`Unrecognized SSL message, plaintext connection?`）。
     */
    @Test
    void plaintextInstanceIsDecidedByTheCrNotByCertSecrets() {
        Map<String, Object> spec = Map.of("http", Map.of("tls", Map.of(
                "selfSignedCertificate", Map.of("disabled", true))));

        // scheme 由 CR 决定（判据只在 ElasticsearchMiddlewareAdapter.tlsEnabled 一处，
        // ElasticsearchRest 与用户管理都读它）
        assertThat(ElasticsearchMiddlewareAdapter.tlsEnabled(spec)).isFalse();
    }

    @Test
    void tlsInstanceIsHttps() {
        assertThat(ElasticsearchMiddlewareAdapter.tlsEnabled(Map.of())).isTrue();
        assertThat(ElasticsearchMiddlewareAdapter.tlsEnabled(null)).isTrue();
        assertThat(ElasticsearchMiddlewareAdapter.tlsEnabled(Map.of("http", Map.of("tls", Map.of(
                "certificate", Map.of("secretName", "my-cert")))))).isTrue();
    }

    // ---------- TLS：CA 从哪个 Secret、哪个键取 ----------

    /**
     * 2026-10-26 真集群实锤：ECK 的 {@code <名>-es-http-ca-internal} 里是 **CA 的密钥对**
     * （{@code tls.crt} / {@code tls.key}），公钥证书 {@code ca.crt} 在
     * {@code <名>-es-http-certs-public} 里。读错 Secret 的后果不是"连不上"，而是
     * 判定成没开 TLS 去连 TLS 端口。
     */
    @Test
    void caPemIsReadFromThePublicCertsSecret() {
        Secret publicCerts = secret(Map.of("ca.crt", "Q0E=", "tls.crt", "Q1JU"));
        Secret caKeyPair = secret(Map.of("tls.crt", "Q1JU", "tls.key", "S0VZ"));

        assertThat(ElasticsearchUserAdapter.caPem(publicCerts, caKeyPair)).isEqualTo("Q0E=");
    }

    @Test
    void noTlsSecretsMeansPlainHttp() {
        assertThat(ElasticsearchUserAdapter.caPem(null, null)).isNull();
    }

    @Test
    void tlsInstanceWithoutUsableCaIsRejectedInsteadOfSilentlyUsingHttp() {
        // 开了 TLS 却找不到 ca.crt：宁可报错，也不要静默退回 http 去撞 TLS 端口
        assertThatThrownBy(() -> ElasticsearchUserAdapter.caPem(
                null, secret(Map.of("tls.crt", "Q1JU", "tls.key", "S0VZ"))))
                .isInstanceOf(com.coding.common.exception.CloudPlatformException.class)
                .hasMessageContaining("ca.crt");

        assertThatThrownBy(() -> ElasticsearchUserAdapter.caPem(secret(Map.of("tls.crt", "Q1JU")), null))
                .hasMessageContaining("ca.crt");
    }

    private static Secret secret(Map<String, String> data) {
        return new SecretBuilder().withNewMetadata().withName("s").endMetadata().withData(data).build();
    }
}
