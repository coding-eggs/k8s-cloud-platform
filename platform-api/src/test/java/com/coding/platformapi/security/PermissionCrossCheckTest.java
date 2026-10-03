package com.coding.platformapi.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PermissionCrossCheckTest {

    @Test
    void flagged_uncovered_bare_endpoint() {
        PermissionRegistry reg = mock(PermissionRegistry.class);
        when(reg.requiredCodes(anyString(), anyString())).thenReturn(Optional.empty());
        when(reg.requiredCodes("POST", "/tenant/create"))
                .thenReturn(Optional.of(Set.of("platform:tenant:provision")));

        var gaps = PermissionCrossCheck.uncoveredEndpoints(
                List.of(new PermissionCrossCheck.Endpoint("POST", "/tenant/create"),
                        new PermissionCrossCheck.Endpoint("POST", "/user/create")), reg);

        assertThat(gaps).extracting(PermissionCrossCheck.Endpoint::pattern)
                .containsExactly("/user/create");
    }

    @Test
    void exempt_endpoints_not_flagged_even_without_rule() {
        PermissionRegistry reg = mock(PermissionRegistry.class);
        when(reg.requiredCodes(anyString(), anyString())).thenReturn(Optional.empty());

        var gaps = PermissionCrossCheck.uncoveredEndpoints(
                List.of(new PermissionCrossCheck.Endpoint("GET", "/resource/context")), reg);

        assertThat(gaps).isEmpty();
    }

    @Test
    void ghost_rules_reported_not_thrown() {
        // §5.3 反向：规则匹配不到任何真实端点 = 幽灵行（只 WARN，绝不抛错）。
        // /user/update 是 v1 有意保留的"计划端点"行；/tenant/create 有对应端点，不算幽灵。
        PermissionRegistry reg = new PermissionRegistry(List.of(
                new PermissionRegistry.Rule("POST", "/tenant/create", "platform:tenant:provision"),
                new PermissionRegistry.Rule("POST", "/user/update", "platform:user:manage")));
        var eps = List.of(new PermissionCrossCheck.Endpoint("POST", "/tenant/create"));

        assertThat(PermissionCrossCheck.ghostRules(eps, reg))
                .extracting(PermissionRegistry.Rule::pattern)
                .containsExactly("/user/update");
    }

    @Test
    void no_ghosts_when_every_rule_hits_an_endpoint() {
        PermissionRegistry reg = new PermissionRegistry(List.of(
                new PermissionRegistry.Rule("POST", "/tenant/create", "platform:tenant:provision")));
        var eps = List.of(new PermissionCrossCheck.Endpoint("POST", "/tenant/create"));

        assertThat(PermissionCrossCheck.ghostRules(eps, reg)).isEmpty();
    }
}
