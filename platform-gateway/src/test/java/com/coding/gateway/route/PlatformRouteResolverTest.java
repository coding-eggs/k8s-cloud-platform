package com.coding.gateway.route;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformRouteResolverTest {

    private static final PlatformRouteProperties.Route API = new PlatformRouteProperties.Route(
            "platform-api", "/api/", "/", URI.create("http://api:8081"), "/api");
    private static final PlatformRouteProperties.Route CLIENTS = new PlatformRouteProperties.Route(
            "platform-auth", "/api/oauth-clients/", "/internal/clients/", URI.create("http://auth:9527"), "/auth");
    private static final PlatformRouteProperties.Route JWKS = new PlatformRouteProperties.Route(
            "platform-auth", "/api/jwks/", "/internal/jwk/", URI.create("http://auth:9527"), "/auth");

    // 书写顺序故意把 "/api/" 放最前：解析必须按最长前缀，与配置顺序无关
    private final PlatformRouteResolver resolver = new PlatformRouteResolver(List.of(API, CLIENTS, JWKS));

    @Test
    void authClientRoute() {
        var r = resolver.resolve("/api/oauth-clients/list").orElseThrow();
        assertThat(r.route()).isEqualTo(CLIENTS);
        assertThat(r.internalPath()).isEqualTo("/internal/clients/list");
    }

    @Test
    void authJwkRoute() {
        var r = resolver.resolve("/api/jwks/generate").orElseThrow();
        assertThat(r.route().service()).isEqualTo("platform-auth");
        assertThat(r.internalPath()).isEqualTo("/internal/jwk/generate");
    }

    @Test
    void everythingElseIsApi() {
        var r = resolver.resolve("/api/pods/x/logs").orElseThrow();
        assertThat(r.route()).isEqualTo(API);
        assertThat(r.internalPath()).isEqualTo("/pods/x/logs");
    }

    @Test
    void prefixEndsAtSlashBoundary() {
        assertThat(resolver.resolve("/api/oauth-clientsX/a").orElseThrow().route()).isEqualTo(API);
        assertThat(resolver.resolve("/api/oauth-clients").orElseThrow().route()).isEqualTo(API);
    }

    @Test
    void forwardPathPrependsContextPath() {
        assertThat(resolver.resolve("/api/oauth-clients/list").orElseThrow().forwardPath())
                .isEqualTo("/auth/internal/clients/list");
        assertThat(resolver.resolve("/api/ws/pod/exec").orElseThrow().forwardPath()).isEqualTo("/api/ws/pod/exec");
    }

    /** 浏览器路径 = 网关路径 = api 实收路径；不带 /api 的请求不属于任何上游 */
    @Test
    void pathOutsideApiPrefixHasNoRoute() {
        assertThat(resolver.resolve("/pods/x/logs")).isEmpty();
        assertThat(resolver.resolve("/api")).isEmpty();
    }
}
