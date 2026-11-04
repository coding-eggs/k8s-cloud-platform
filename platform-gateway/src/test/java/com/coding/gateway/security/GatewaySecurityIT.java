package com.coding.gateway.security;

import com.coding.common.components.jwt.JwtPermissionResolver;
import com.coding.common.exception.EnumResponseType;
import com.coding.data.mapper.auth.PlatformPermissionMapper;
import com.coding.data.mapper.system.PlatformAuditLogMapper;
import com.coding.data.models.auth.PlatformPermission;
import com.coding.gateway.support.TestJwt;
import com.coding.gateway.support.UpstreamStub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 端到端：真安全链 + 本地 RSA 签 token + 桩上游。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestJwt.LocalKeys.class)
class GatewaySecurityIT {

    static final UpstreamStub API = new UpstreamStub();
    static final UpstreamStub AUTH = new UpstreamStub();

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
        client.post().uri("/oauth-clients/list").header("Authorization", "Bearer " + TestJwt.token("other-client"))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void missingCodeIs403AndUpstreamUntouched() throws Exception {
        when(permissionResolver.resolve(anyString(), any())).thenReturn(List.of());

        client.post().uri("/oauth-clients/list").header("Authorization", "Bearer " + TestJwt.token())
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
        String jwt = TestJwt.token();

        client.post().uri("/oauth-clients/list").header("Authorization", "Bearer " + jwt)
                .exchange().expectStatus().isOk();

        assertThat(AUTH.take().authorization()).isEqualTo("Bearer " + jwt);
    }

    @Test
    void queryTokenNotAcceptedOutsideWs() {
        client.get().uri("/pods/a/logs?access_token=" + TestJwt.token())
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void alertWebhookSharedSecretIsNotParsedAsJwt() throws Exception {
        client.post().uri("/alert/webhook").header("Authorization", "Bearer sd")
                .exchange().expectStatus().isOk();

        assertThat(API.take().authorization()).isEqualTo("Bearer sd");
    }

}
