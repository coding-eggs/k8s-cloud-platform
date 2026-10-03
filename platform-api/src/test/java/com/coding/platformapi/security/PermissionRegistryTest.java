package com.coding.platformapi.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionRegistryTest {

    private PermissionRegistry reg() {
        return new PermissionRegistry(List.of(
                new PermissionRegistry.Rule("POST", "/tenant/member/add", "tenant:member:manage"),
                new PermissionRegistry.Rule("POST", "/tenant/member/add", "platform:member:manage"),
                new PermissionRegistry.Rule("POST", "/tenant/create", "platform:tenant:provision")
        ));
    }

    @Test
    void any_of_multiple_codes_for_same_url() {
        assertThat(reg().requiredCodes("POST", "/tenant/member/add"))
                .contains(Set.of("tenant:member:manage", "platform:member:manage"));
    }

    @Test
    void no_rule_returns_empty() {
        assertThat(reg().requiredCodes("POST", "/tenant/member/add")).isPresent();
        assertThat(reg().requiredCodes("GET", "/anything")).isEmpty();
    }

    @Test
    void method_is_case_insensitive() {
        assertThat(reg().requiredCodes("post", "/tenant/create"))
                .contains(Set.of("platform:tenant:provision"));
    }

    @Test
    void ant_wildcard_pattern_matches_deeper_paths() {
        var reg = new PermissionRegistry(List.of(
                new PermissionRegistry.Rule("POST", "/tenant/**", "tenant:any")));
        assertThat(reg.requiredCodes("POST", "/tenant/member/add"))
                .contains(Set.of("tenant:any"));
        assertThat(reg.requiredCodes("POST", "/tenant/create"))
                .contains(Set.of("tenant:any"));
        assertThat(reg.requiredCodes("POST", "/role/list")).isEmpty();
    }

    @Test
    void replace_rules_atomically_swaps_snapshot() {
        var reg = reg();
        List<PermissionRegistry.Rule> before = reg.rules(); // 换表前拿到的引用
        reg.replaceRules(List.of(new PermissionRegistry.Rule("GET", "/new/**", "platform:new")));
        // 新快照整体生效：新规则命中、旧规则消失（读方只看到整旧或整新，不混合）
        assertThat(reg.requiredCodes("GET", "/new/x")).contains(Set.of("platform:new"));
        assertThat(reg.requiredCodes("POST", "/tenant/create")).isEmpty();
        // 换表前的不可变引用不受影响
        assertThat(before).hasSize(3);
    }

    @Test
    void replace_rules_accepts_empty_collection() {
        var reg = reg();
        reg.replaceRules(List.of());
        assertThat(reg.rules()).isEmpty();
        assertThat(reg.requiredCodes("POST", "/tenant/member/add")).isEmpty();
    }
}
