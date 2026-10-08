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

    /** 平台侧标记：持有任意 PLATFORM-scope 角色码（不限于内置 admin）即成立 —— 这是 k8s-server
     *  替代 `PLATFORM:admin` 的判据，所以"自定义平台角色也成立"必须有测试钉住。 */
    @Test
    void marks_platform_side_for_any_platform_scoped_role_code() {
        var data = new LinkedHashMap<String, Object>();
        data.put("platformRoles", List.of("audit-reader"));

        JwtAuthenticationToken tok = (JwtAuthenticationToken)
                new PlatformJwtAuthenticationConverter("data").convert(jwt(data));

        assertThat(authorities(tok)).contains(PlatformJwtAuthenticationConverter.PLATFORM_SCOPE_AUTHORITY);
    }

    /** 租户成员：platformRoles 为空（签发期已按 scope='PLATFORM' 过滤）→ 不得出现平台侧标记。 */
    @Test
    void tenant_member_has_no_platform_side_mark() {
        var data = new LinkedHashMap<String, Object>();
        data.put("platformRoles", List.of());
        data.put("permissions", List.of("tenant:workload:list"));
        data.put("tenantInfo", Map.of("tenantId", "t1"));

        JwtAuthenticationToken tok = (JwtAuthenticationToken)
                new PlatformJwtAuthenticationConverter("data").convert(jwt(data));

        assertThat(authorities(tok)).doesNotContain(PlatformJwtAuthenticationConverter.PLATFORM_SCOPE_AUTHORITY);
    }

    /** claim 缺失（老 token / 非本平台 token）同样不得凭空获得平台侧身份。 */
    @Test
    void missing_platform_roles_claim_has_no_platform_side_mark() {
        var data = new LinkedHashMap<String, Object>();
        data.put("permissions", List.of("tenant:workload:list"));

        JwtAuthenticationToken tok = (JwtAuthenticationToken)
                new PlatformJwtAuthenticationConverter("data").convert(jwt(data));

        assertThat(authorities(tok)).doesNotContain(PlatformJwtAuthenticationConverter.PLATFORM_SCOPE_AUTHORITY);
    }

    /** 空白角色码不算数（与 PLATFORM:<code> 展开同一判据）。 */
    @Test
    void blank_role_code_does_not_grant_platform_side() {
        var data = new LinkedHashMap<String, Object>();
        data.put("platformRoles", List.of("  ", ""));

        JwtAuthenticationToken tok = (JwtAuthenticationToken)
                new PlatformJwtAuthenticationConverter("data").convert(jwt(data));

        assertThat(authorities(tok)).doesNotContain(PlatformJwtAuthenticationConverter.PLATFORM_SCOPE_AUTHORITY);
    }

    private Set<String> authorities(JwtAuthenticationToken tok) {
        return tok.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }
}
