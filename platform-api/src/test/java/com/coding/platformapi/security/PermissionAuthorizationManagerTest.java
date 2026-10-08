package com.coding.platformapi.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PermissionAuthorizationManagerTest {

    private RequestAuthorizationContext ctx(String m, String p) {
        MockHttpServletRequest r = new MockHttpServletRequest(m, p);
        return new RequestAuthorizationContext(r);
    }

    private UsernamePasswordAuthenticationToken auth(String... perms) {
        List<GrantedAuthority> list = Arrays.stream(perms)
                .map(SimpleGrantedAuthority::new)
                .<GrantedAuthority>map(x -> x)
                .toList();
        // 带 GrantedAuthority 列表的构造器已置 authenticated=true（不能再 setAuthenticated）
        return new UsernamePasswordAuthenticationToken("u", "p", list);
    }

    private PermissionRegistry registryWithTenantCreate() {
        PermissionRegistry reg = mock(PermissionRegistry.class);
        when(reg.requiredCodes("POST", "/tenant/create"))
                .thenReturn(Optional.of(Set.of("platform:tenant:provision")));
        return reg;
    }

    @Test
    void granted_when_has_one_required_perm() {
        var mgr = new PermissionAuthorizationManager(registryWithTenantCreate());
        AuthorizationResult d = mgr.authorize(() -> auth("PERM:platform:tenant:provision"),
                ctx("POST", "/tenant/create"));
        assertThat(d.isGranted()).isTrue();
    }

    @Test
    void granted_when_multi_code_anyof_matches_second() {
        // spec §5.4 自管理/委托契约：/tenant/member/add 接受多 code ANY-of，
        // 仅持有第二个 code 也必须放行
        PermissionRegistry reg = mock(PermissionRegistry.class);
        when(reg.requiredCodes("POST", "/tenant/member/add"))
                .thenReturn(Optional.of(Set.of("tenant:member:manage", "platform:member:manage")));
        var mgr = new PermissionAuthorizationManager(reg);
        AuthorizationResult d = mgr.authorize(() -> auth("PERM:platform:member:manage"),
                ctx("POST", "/tenant/member/add"));
        assertThat(d.isGranted()).isTrue();
    }

    @Test
    void denied_when_no_required_perm() {
        var mgr = new PermissionAuthorizationManager(registryWithTenantCreate());
        AuthorizationResult d = mgr.authorize(() -> auth("PERM:tenant:overview:view"),
                ctx("POST", "/tenant/create"));
        assertThat(d.isGranted()).isFalse();
    }

    @Test
    void denied_when_unauthenticated_even_on_exempt_path() {
        PermissionRegistry reg = mock(PermissionRegistry.class);
        var mgr = new PermissionAuthorizationManager(reg);
        var anonymous = new UsernamePasswordAuthenticationToken("u", "p"); // authenticated=false
        AuthorizationResult d = mgr.authorize(() -> anonymous, ctx("GET", "/pods"));
        assertThat(d.isGranted()).isFalse();
    }

    @Test
    void denied_when_spring_anonymous_token_on_exempt_path() {
        // AnonymousAuthenticationToken.isAuthenticated() 恒为 true，但语义上未登录 → 豁免路径也必须拒
        PermissionRegistry reg = mock(PermissionRegistry.class);
        var mgr = new PermissionAuthorizationManager(reg);
        var anon = new org.springframework.security.authentication.AnonymousAuthenticationToken(
                "key", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        AuthorizationResult d = mgr.authorize(() -> anon, ctx("GET", "/user/me"));
        assertThat(d.isGranted()).isFalse();
    }

    @Test
    void exempt_path_granted_for_authenticated_without_any_rule() {
        PermissionRegistry reg = mock(PermissionRegistry.class); // requiredCodes 默认返回 empty
        var mgr = new PermissionAuthorizationManager(reg);
        AuthorizationResult d = mgr.authorize(() -> auth("ROLE_USER"), ctx("GET", "/context"));
        assertThat(d.isGranted()).isTrue();
    }

    @Test
    void denied_when_no_rule_and_not_exempt() {
        PermissionRegistry reg = mock(PermissionRegistry.class);
        var mgr = new PermissionAuthorizationManager(reg);
        AuthorizationResult d = mgr.authorize(() -> auth("PERM:anything"), ctx("POST", "/unknown/path"));
        assertThat(d.isGranted()).isFalse();
    }

    @Test
    void row_on_exempt_path_denied_when_code_not_held() {
        // spec §5.1：权限行命中 > 豁免 —— 行可收紧豁免路径；命中行但无 code → 拒，不回落豁免
        PermissionRegistry reg = mock(PermissionRegistry.class);
        when(reg.requiredCodes("GET", "/context"))
                .thenReturn(Optional.of(Set.of("tenant:workload:list")));
        var mgr = new PermissionAuthorizationManager(reg);
        AuthorizationResult d = mgr.authorize(() -> auth("PERM:platform:cluster:manage"),
                ctx("GET", "/context"));
        assertThat(d.isGranted()).isFalse();
    }

    @Test
    void row_on_exempt_path_granted_when_code_held() {
        PermissionRegistry reg = mock(PermissionRegistry.class);
        when(reg.requiredCodes("GET", "/context"))
                .thenReturn(Optional.of(Set.of("tenant:workload:list")));
        var mgr = new PermissionAuthorizationManager(reg);
        AuthorizationResult d = mgr.authorize(() -> auth("PERM:tenant:workload:list"),
                ctx("GET", "/context"));
        assertThat(d.isGranted()).isTrue();
    }
}
