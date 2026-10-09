package com.coding.common.components.jwt;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * claim → authority 映射。权限码自 2026-10-09 起不再随 token 下发，故本测试同时钉住两条来源：
 * claim 有就用 claim（回滚期），没有就按需解析（常态），解析失败 fail-closed 且不掀翻认证链。
 */
class PlatformJwtAuthenticationConverterTest {

    /** 常态：token 不带 permissions，权限码由解析器给。 */
    private static final JwtPermissionResolver NO_RESOLVE = JwtPermissionResolver.noop();

    private Jwt jwt(Map<String, Object> data) {
        return jwt(data, null);
    }

    private Jwt jwt(Map<String, Object> data, String subject) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("data", data);
        if (subject != null) {
            claims.put("sub", subject);
        }
        return new Jwt("raw", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"), claims);
    }

    private PlatformJwtAuthenticationConverter converter(String dataKey) {
        return new PlatformJwtAuthenticationConverter(dataKey, NO_RESOLVE);
    }

    @Test
    void expands_permissions_to_PERM_authorities() {
        var data = new LinkedHashMap<String, Object>();
        data.put("platformRoles", List.of("admin"));
        data.put("permissions", List.of("platform:tenant:read", "tenant:member:manage"));

        JwtAuthenticationToken tok = (JwtAuthenticationToken) converter("data").convert(jwt(data));

        assertThat(authorities(tok))
                .contains("PLATFORM:admin", "PERM:platform:tenant:read", "PERM:tenant:member:manage");
    }

    /** 常态路径：claim 无 permissions → 调解析器，并把 (username, tenantId) 传对。 */
    @Test
    void resolves_permissions_when_claim_absent() {
        var asked = new ArrayList<String>();
        var resolver = (JwtPermissionResolver) (username, tenantId) -> {
            asked.add(username + "|" + tenantId);
            return List.of("tenant:workload:list");
        };
        var data = new LinkedHashMap<String, Object>();
        data.put("username", "alice");
        data.put("platformRoles", List.of("audit-reader"));
        data.put("tenantInfo", Map.of("tenantId", "t1"));

        JwtAuthenticationToken tok = (JwtAuthenticationToken)
                new PlatformJwtAuthenticationConverter("data", resolver).convert(jwt(data));

        assertThat(asked).containsExactly("alice|t1");
        assertThat(authorities(tok)).contains("PERM:tenant:workload:list");
    }

    /** 平台视图（base token，无 tenantInfo）→ tenantId 传 null，实现方只算平台族。 */
    @Test
    void resolves_with_null_tenant_for_base_token() {
        var asked = new ArrayList<String>();
        var resolver = (JwtPermissionResolver) (username, tenantId) -> {
            asked.add(String.valueOf(tenantId));
            return List.of();
        };
        var data = new LinkedHashMap<String, Object>();
        data.put("username", "admin");

        new PlatformJwtAuthenticationConverter("data", resolver).convert(jwt(data));

        assertThat(asked).containsExactly("null");
    }

    /** claim 存在时**不得**再解析（回滚开关打开期间行为必须与改动前逐字一致）。 */
    @Test
    void claim_wins_and_resolver_is_not_consulted() {
        var blown = (JwtPermissionResolver) (username, tenantId) -> {
            throw new AssertionError("claim 已在，不该解析");
        };
        var data = new LinkedHashMap<String, Object>();
        data.put("username", "alice");
        data.put("permissions", List.of("platform:tenant:read"));

        JwtAuthenticationToken tok = (JwtAuthenticationToken)
                new PlatformJwtAuthenticationConverter("data", blown).convert(jwt(data));

        assertThat(authorities(tok)).contains("PERM:platform:tenant:read");
    }

    /** 解析抛异常 = 没有权限码（fail-closed），但不能让认证链整体失败（否则 DB 抖动会让全站 401）。 */
    @Test
    void resolver_failure_is_fail_closed_not_fatal() {
        var dead = (JwtPermissionResolver) (username, tenantId) -> {
            throw new IllegalStateException("db down");
        };
        var data = new LinkedHashMap<String, Object>();
        data.put("username", "alice");
        data.put("platformRoles", List.of("admin"));

        JwtAuthenticationToken tok = (JwtAuthenticationToken)
                new PlatformJwtAuthenticationConverter("data", dead).convert(jwt(data));

        assertThat(authorities(tok))
                .contains("PLATFORM:admin", PlatformJwtAuthenticationConverter.PLATFORM_SCOPE_AUTHORITY)
                .noneMatch(a -> a.startsWith("PERM:"));
    }

    /** 解析器返回 null 也不能 NPE（接口约定返回空集合，实现方仍可能犯懒）。 */
    @Test
    void null_from_resolver_is_treated_as_empty() {
        var lazy = (JwtPermissionResolver) (username, tenantId) -> null;
        var data = new LinkedHashMap<String, Object>();
        data.put("platformRoles", List.of("admin"));

        JwtAuthenticationToken tok = (JwtAuthenticationToken)
                new PlatformJwtAuthenticationConverter("data", lazy).convert(jwt(data));

        assertThat(authorities(tok)).noneMatch(a -> a.startsWith("PERM:"));
    }

    /** claim 无 username 时回退到 sub（老 token / 异构 token 的兜底）。 */
    @Test
    void resolver_falls_back_to_subject_when_username_missing() {
        var asked = new ArrayList<String>();
        var resolver = (JwtPermissionResolver) (username, tenantId) -> {
            asked.add(username);
            return List.of();
        };

        new PlatformJwtAuthenticationConverter("data", resolver).convert(jwt(new LinkedHashMap<>(), "bob"));

        assertThat(asked).containsExactly("bob");
    }

    /** 空白权限码不生成 authority（claim 路径的既有行为，别被重构改掉）。 */
    @Test
    void blank_permission_codes_are_skipped() {
        var data = new LinkedHashMap<String, Object>();
        data.put("permissions", List.of("  ", "", "tenant:workload:list"));

        JwtAuthenticationToken tok = (JwtAuthenticationToken) converter("data").convert(jwt(data));

        assertThat(authorities(tok)).containsExactly("PERM:tenant:workload:list");
    }

    /** 平台侧标记：持有任意 PLATFORM-scope 角色码（不限于内置 admin）即成立 —— 这是 k8s-server
     *  替代 `PLATFORM:admin` 的判据，所以"自定义平台角色也成立"必须有测试钉住。 */
    @Test
    void marks_platform_side_for_any_platform_scoped_role_code() {
        var data = new LinkedHashMap<String, Object>();
        data.put("platformRoles", List.of("audit-reader"));

        JwtAuthenticationToken tok = (JwtAuthenticationToken) converter("data").convert(jwt(data));

        assertThat(authorities(tok)).contains(PlatformJwtAuthenticationConverter.PLATFORM_SCOPE_AUTHORITY);
    }

    /** 租户成员：platformRoles 为空（签发期已按 scope='PLATFORM' 过滤）→ 不得出现平台侧标记。 */
    @Test
    void tenant_member_has_no_platform_side_mark() {
        var data = new LinkedHashMap<String, Object>();
        data.put("platformRoles", List.of());
        data.put("permissions", List.of("tenant:workload:list"));
        data.put("tenantInfo", Map.of("tenantId", "t1"));

        JwtAuthenticationToken tok = (JwtAuthenticationToken) converter("data").convert(jwt(data));

        assertThat(authorities(tok)).doesNotContain(PlatformJwtAuthenticationConverter.PLATFORM_SCOPE_AUTHORITY);
    }

    /** claim 缺失（老 token / 非本平台 token）同样不得凭空获得平台侧身份。 */
    @Test
    void missing_platform_roles_claim_has_no_platform_side_mark() {
        var data = new LinkedHashMap<String, Object>();
        data.put("permissions", List.of("tenant:workload:list"));

        JwtAuthenticationToken tok = (JwtAuthenticationToken) converter("data").convert(jwt(data));

        assertThat(authorities(tok)).doesNotContain(PlatformJwtAuthenticationConverter.PLATFORM_SCOPE_AUTHORITY);
    }

    /** 空白角色码不算数（与 PLATFORM:<code> 展开同一判据）。 */
    @Test
    void blank_role_code_does_not_grant_platform_side() {
        var data = new LinkedHashMap<String, Object>();
        data.put("platformRoles", List.of("  ", ""));

        JwtAuthenticationToken tok = (JwtAuthenticationToken) converter("data").convert(jwt(data));

        assertThat(authorities(tok)).doesNotContain(PlatformJwtAuthenticationConverter.PLATFORM_SCOPE_AUTHORITY);
    }

    private Set<String> authorities(JwtAuthenticationToken tok) {
        return tok.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }
}
