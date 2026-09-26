package com.coding.common.components.jwt;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformJwtAuthenticationConverterTest {

    private Jwt jwt(Map<String, Object> data) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("data", data);
        return new Jwt("raw", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"), claims);
    }

    @Test
    void expands_permissions_to_PERM_authorities() {
        var data = new LinkedHashMap<String, Object>();
        data.put("platformRoles", List.of("admin"));
        data.put("permissions", List.of("platform:tenant:read", "tenant:member:manage"));

        JwtAuthenticationToken tok = (JwtAuthenticationToken)
                new PlatformJwtAuthenticationConverter("data").convert(jwt(data));

        Set<String> a = tok.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        assertThat(a).contains("PLATFORM:admin", "PERM:platform:tenant:read", "PERM:tenant:member:manage");
    }
}
