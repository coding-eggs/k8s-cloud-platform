package com.coding.platformapi.security;

import com.coding.data.models.system.TokenUserInfo;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
}
