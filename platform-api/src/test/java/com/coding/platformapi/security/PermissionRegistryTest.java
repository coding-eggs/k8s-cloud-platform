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
}
