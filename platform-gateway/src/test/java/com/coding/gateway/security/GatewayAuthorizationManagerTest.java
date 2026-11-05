package com.coding.gateway.security;

import com.coding.common.components.permission.PermissionDecider;
import com.coding.common.components.permission.PermissionRegistry;
import com.coding.gateway.route.PlatformRouteProperties;
import com.coding.gateway.route.PlatformRouteResolver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayAuthorizationManagerTest {

    private final PlatformRouteResolver routes = new PlatformRouteResolver(List.of(
            new PlatformRouteProperties.Route("platform-api", "/api/", "/", URI.create("http://api:8081"), "/api"),
            new PlatformRouteProperties.Route("platform-auth", "/api/oauth-clients/", "/internal/clients/",
                    URI.create("http://auth:9527"), "/auth")));
    private final PermissionRegistry registry = new PermissionRegistry(List.of(
            new PermissionRegistry.Rule("platform-auth", "POST", "/internal/clients/list", "platform:oauth-client:view")));
    private final GatewayAuthorizationManager manager =
            new GatewayAuthorizationManager(routes, new PermissionDecider(registry));

    private static MockServerWebExchange post(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path));
    }

    private static Mono<Authentication> user(String... authorities) {
        TestingAuthenticationToken t = new TestingAuthenticationToken("u", "n/a", authorities);
        t.setAuthenticated(true);
        return Mono.just(t);
    }

    private boolean granted(Mono<Authentication> auth, MockServerWebExchange ex) {
        AuthorizationResult r = manager.authorize(auth, new AuthorizationContext(ex)).block();
        return r != null && r.isGranted();
    }

    @Test
    void unauthenticatedDenied() {
        assertThat(granted(Mono.empty(), post("/api/user/me"))).isFalse();
    }

    @Test
    void holderOfCodeGrantedOnAuthRoute() {
        assertThat(granted(user("PERM:platform:oauth-client:view"), post("/api/oauth-clients/list"))).isTrue();
    }

    @Test
    void missingCodeDeniedAndCodesRecordedForAudit() {
        MockServerWebExchange ex = post("/api/oauth-clients/list");

        assertThat(granted(user("PERM:other"), ex)).isFalse();
        assertThat(ex.<Set<String>>getAttribute(GatewayAuthorizationManager.DENIED_CODES_ATTR))
                .containsExactly("platform:oauth-client:view");
    }

    @Test
    void exemptPathWithoutRowGranted() {
        assertThat(granted(user(), MockServerWebExchange.from(MockServerHttpRequest.get("/api/user/me")))).isTrue();
    }

    @Test
    void unknownPathWithoutRowDeniedWithoutCodes() {
        MockServerWebExchange ex = post("/api/nobody/has/this");

        assertThat(granted(user("PERM:platform:oauth-client:view"), ex)).isFalse();
        assertThat((Object) ex.getAttribute(GatewayAuthorizationManager.DENIED_CODES_ATTR)).isNull();
    }
}
