package com.coding.gateway.route;

import com.coding.data.mapper.auth.PlatformPermissionMapper;
import com.coding.gateway.support.PermitAllSecurity;
import com.coding.gateway.support.UpstreamStub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PermitAllSecurity.class)
class GatewayRoutingIT {

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

    @LocalServerPort
    int port;

    WebTestClient client;

    @BeforeEach
    void client() {
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
    }

    @Test
    void authClientRouteRewritesToInternalPathKeepingQueryAndAuthorization() throws Exception {
        client.post().uri("/oauth-clients/list?x=1").header("Authorization", "Bearer t0k")
                .exchange().expectStatus().isOk();

        UpstreamStub.Received r = AUTH.take();
        assertThat(r.method()).isEqualTo("POST");
        assertThat(r.uri()).isEqualTo("/auth/internal/clients/list?x=1");
        assertThat(r.authorization()).isEqualTo("Bearer t0k");
    }

    @Test
    void apiRouteKeepsPathAndAccessTokenQuery() throws Exception {
        client.get().uri("/pods/a/logs?access_token=abc&tenantId=t1").exchange().expectStatus().isOk();

        assertThat(API.take().uri()).isEqualTo("/pods/a/logs?access_token=abc&tenantId=t1");
    }
}
