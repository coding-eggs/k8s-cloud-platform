package com.coding.gateway.audit;

import com.coding.data.mapper.system.PlatformAuditLogMapper;
import com.coding.data.models.system.PlatformAuditLog;
import com.coding.gateway.security.GatewayAuthorizationManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GatewayDeniedAuditorTest {

    private final PlatformAuditLogMapper mapper = mock(PlatformAuditLogMapper.class);
    private final GatewayDeniedAuditor auditor = new GatewayDeniedAuditor(mapper, "data");

    private static JwtAuthenticationToken alice() {
        Jwt jwt = Jwt.withTokenValue("xyz").header("alg", "RS256").subject("alice")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .claim("data", Map.of("username", "alice", "displayName", "Alice",
                        "tenantInfo", Map.of("tenantId", "t1")))
                .build();
        return new JwtAuthenticationToken(jwt, List.of());
    }

    private PlatformAuditLog recorded() {
        ArgumentCaptor<PlatformAuditLog> c = ArgumentCaptor.forClass(PlatformAuditLog.class);
        verify(mapper).insert(c.capture());
        return c.getValue();
    }

    @Test
    void deniedRecordCarriesIdentityCodesAndPathWithoutQuery() {
        MockServerWebExchange ex = MockServerWebExchange.from(MockServerHttpRequest
                .get("/ws/pod/exec?access_token=xyz")
                .header("X-Forwarded-For", "10.0.0.9, 10.0.0.1")
                .header("User-Agent", "ua"));
        ex.getAttributes().put(GatewayAuthorizationManager.DENIED_CODES_ATTR,
                new LinkedHashSet<>(List.of("platform:oauth-client:manage")));

        StepVerifier.create(auditor.record(ex, alice())).verifyComplete();

        PlatformAuditLog r = recorded();
        assertThat(r.getResult()).isEqualTo("DENIED");
        assertThat(r.getAction()).isEqualTo("[platform:oauth-client:manage]");
        assertThat(r.getRequestPath()).isEqualTo("/ws/pod/exec");
        assertThat(r.getHttpMethod()).isEqualTo("GET");
        assertThat(r.getActorUsername()).isEqualTo("alice");
        assertThat(r.getActorDisplayName()).isEqualTo("Alice");
        assertThat(r.getTenantId()).isEqualTo("t1");
        assertThat(r.getClientIp()).isEqualTo("10.0.0.9");
        assertThat(r.getUserAgent()).isEqualTo("ua");
        assertThat(r.getOccurredAt()).isNotNull();
        assertThat(r.toString()).doesNotContain("xyz");
    }

    @Test
    void realIpHeaderWinsOverForwardedFor() {
        MockServerWebExchange ex = MockServerWebExchange.from(MockServerHttpRequest.post("/x")
                .header("X-Real-IP", "1.2.3.4").header("X-Forwarded-For", "5.6.7.8"));

        StepVerifier.create(auditor.record(ex, alice())).verifyComplete();

        assertThat(recorded().getClientIp()).isEqualTo("1.2.3.4");
    }

    /** 与 api 现状一致：没命中权限行的拒绝也记一条，action 为空 */
    @Test
    void denialWithoutCodesStillRecordedWithNullAction() {
        StepVerifier.create(auditor.record(MockServerWebExchange.from(MockServerHttpRequest.post("/nobody")), alice()))
                .verifyComplete();

        assertThat(recorded().getAction()).isNull();
    }

    @Test
    void writeFailureNeverPropagates() {
        when(mapper.insert(any())).thenThrow(new RuntimeException("db down"));

        StepVerifier.create(auditor.record(MockServerWebExchange.from(MockServerHttpRequest.post("/x")), alice()))
                .verifyComplete();
    }
}
