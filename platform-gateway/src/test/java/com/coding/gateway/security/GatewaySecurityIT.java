package com.coding.gateway.security;

import com.coding.common.components.jwt.JwtPermissionResolver;
import com.coding.common.components.jwt.PlatformJwtValidators;
import com.coding.common.exception.EnumResponseType;
import com.coding.data.mapper.auth.PlatformPermissionMapper;
import com.coding.data.mapper.system.PlatformAuditLogMapper;
import com.coding.data.models.auth.PlatformPermission;
import com.coding.gateway.support.UpstreamStub;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 端到端：真安全链 + 本地 RSA 签 token + 桩上游。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewaySecurityIT {

    static final UpstreamStub API = new UpstreamStub();
    static final UpstreamStub AUTH = new UpstreamStub();
    static final KeyPair KEYS = rsa();

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry r) {
        r.add("PLATFORM_API_URL", API::baseUrl);
        r.add("PLATFORM_AUTH_URL", AUTH::baseUrl);
    }

    @AfterAll
    static void stop() {
        API.close();
        AUTH.close();
    }

    @TestConfiguration
    static class LocalKeys {
        /** 换掉 jwk-set-uri 解码器：同一份 aud 白名单，验签用本地公钥 */
        @Bean
        @Primary
        ReactiveJwtDecoder localDecoder() {
            NimbusReactiveJwtDecoder d = NimbusReactiveJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
            d.setJwtValidator(PlatformJwtValidators.withAudiences(List.of("platform-web-client")));
            return d;
        }
    }

    @MockitoBean
    PlatformPermissionMapper permissionMapper;

    @MockitoBean
    JwtPermissionResolver permissionResolver;

    /** 被拒审计写库：不碰真库 */
    @MockitoBean
    PlatformAuditLogMapper auditLogMapper;

    @Autowired
    PermissionRegistryReloader reloader;

    @LocalServerPort
    int port;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        PlatformPermission row = new PlatformPermission();
        row.setDomain("API");
        row.setService("platform-auth");
        row.setAction("POST");
        row.setResource("/internal/clients/list");
        row.setCode("platform:oauth-client:view");
        when(permissionMapper.selectAllActive()).thenReturn(List.of(row));
        reloader.reload();
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
    }

    @Test
    void noTokenIs401WithLoginBody() {
        client.post().uri("/oauth-clients/list").exchange()
                .expectStatus().isUnauthorized()
                .expectBody().json("{\"code\":" + EnumResponseType.USER_UN_LOGIN.getCode()
                        + ",\"msg\":\"" + EnumResponseType.USER_UN_LOGIN.getMsg() + "\",\"data\":null}", true);
    }

    @Test
    void foreignAudienceIs401() {
        client.post().uri("/oauth-clients/list").header("Authorization", "Bearer " + token("other-client"))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void missingCodeIs403AndUpstreamUntouched() throws Exception {
        when(permissionResolver.resolve(anyString(), any())).thenReturn(List.of());

        client.post().uri("/oauth-clients/list").header("Authorization", "Bearer " + token("platform-web-client"))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().json("{\"code\":" + EnumResponseType.NON_AUTH_ENTRY_POINT.getCode()
                        + ",\"msg\":\"" + EnumResponseType.NON_AUTH_ENTRY_POINT.getMsg() + "\",\"data\":null}", true);
        assertThat(AUTH.nothingReceived()).isTrue();
        verify(auditLogMapper, timeout(2000)).insert(any());
    }

    @Test
    void holderPassesAndUpstreamSeesSameAuthorization() throws Exception {
        when(permissionResolver.resolve(anyString(), any())).thenReturn(List.of("platform:oauth-client:view"));
        String jwt = token("platform-web-client");

        client.post().uri("/oauth-clients/list").header("Authorization", "Bearer " + jwt)
                .exchange().expectStatus().isOk();

        assertThat(AUTH.take().authorization()).isEqualTo("Bearer " + jwt);
    }

    @Test
    void queryTokenNotAcceptedOutsideWs() {
        client.get().uri("/pods/a/logs?access_token=" + token("platform-web-client"))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void alertWebhookSharedSecretIsNotParsedAsJwt() throws Exception {
        client.post().uri("/alert/webhook").header("Authorization", "Bearer sd")
                .exchange().expectStatus().isOk();

        assertThat(API.take().authorization()).isEqualTo("Bearer sd");
    }

    private static String token(String audience) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject("alice")
                    .audience(audience)
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .claim("data", Map.of("username", "alice"))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner(KEYS.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
