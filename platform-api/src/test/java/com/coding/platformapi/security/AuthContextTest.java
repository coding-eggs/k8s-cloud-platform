package com.coding.platformapi.security;

import com.coding.data.models.system.TokenUserInfo;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthContextTest {

    private final AuthContext auth = new AuthContext(JsonMapper.builder().build(), "data");

    @Test void extracts_tenant_info_from_data_claim() {
        Jwt jwt = jwt(Map.of("data", Map.of(
                "username", "alice",
                "tenantInfo", Map.of("userId", "u1", "tenantId", "t1"))));
        TokenUserInfo info = auth.current(new JwtAuthenticationToken(jwt, List.of()));
        assertThat(info).isNotNull();
        assertThat(info.getUsername()).isEqualTo("alice");
        assertThat(info.getTenantInfo().getTenantId()).isEqualTo("t1");
    }

    @Test void admin_token_without_data_claim_returns_null() {
        Jwt jwt = jwt(Map.of("sub", "client-1"));
        assertThat(auth.current(new JwtAuthenticationToken(jwt, List.of()))).isNull();
    }

    @Test void non_jwt_principal_returns_null() {
        assertThat(auth.current(new UsernamePasswordAuthenticationToken(
                "someone", "n/a", List.of(new SimpleGrantedAuthority("ROLE_X"))))).isNull();
        assertThat(auth.current(null)).isNull();
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "none"), claims);
    }

    // ---- 生效租户（调有租户维度的资源时必须给一个）----

    @Test void the_tokens_tenant_wins_over_whatever_the_request_says() {
        // 自管轨：token 是权威身份源。请求里传别人的租户，k8s-server 会判 TENANT_MISMATCH ——
        // 这里干脆忽略请求值，不给它机会
        withAuth(jwt(Map.of("data", Map.of("username", "alice",
                "tenantInfo", Map.of("userId", "u1", "tenantId", "t1")))));

        assertThat(auth.effectiveTenantId("t-other")).isEqualTo("t1");

        clearAuth();
    }

    @Test void a_platform_admin_uses_the_tenant_the_request_names() {
        // 代管轨：base token 没有 tenantInfo，而 k8s-server 对平台侧调用要求**显式指定 tenantId**
        //（"管理员代操作需显式指定 tenantId"就是它抛的）→ 用前端顶栏级联里选的那个租户
        withAuth(jwt(Map.of("data", Map.of("username", "admin", "platformRoles", List.of("admin")))));

        assertThat(auth.effectiveTenantId("t1")).isEqualTo("t1");

        clearAuth();
    }

    @Test void an_admin_with_no_tenant_in_the_request_stays_null() {
        // 没传就保持 null，让 k8s-server 照旧拒 —— 不在这里猜一个租户
        withAuth(jwt(Map.of("data", Map.of("username", "admin"))));

        assertThat(auth.effectiveTenantId(null)).isNull();

        clearAuth();
    }

    private static void withAuth(Jwt jwt) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    private static void clearAuth() {
        SecurityContextHolder.clearContext();
    }
}
