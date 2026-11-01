package com.coding.platformapi.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PermissionRegistry#runtimeMatches} 是**运行时判权**与「按 URL 反查」共用的判据。
 *
 * <p>它必须与 {@link PermissionRegistry#requiredCodes} 完全同口径：方法忽略大小写、外加盟单里的
 * {@code *} 在运行时**不**当通配（现状如此，反查接口要如实反映，不能比真实鉴权更宽松）。
 * 已有的 {@link PermissionRegistry#matches} 保留反向核账语义（认 {@code *}），两者不能混用。
 */
class PermissionRegistryRuntimeMatchTest {

    private final PermissionRegistry reg = new PermissionRegistry(List.of(
            new PermissionRegistry.Rule("GET", "/workloads/{name}", "tenant:workload:get"),
            new PermissionRegistry.Rule("POST", "/role/**", "platform:role:manage"),
            new PermissionRegistry.Rule("*", "/anything/**", "platform:any")
    ));

    @Test
    void matches_exact_path_and_method() {
        var rule = new PermissionRegistry.Rule("GET", "/workloads/{name}", "tenant:workload:get");
        assertThat(reg.runtimeMatches(rule, "GET", "/workloads/list")).isTrue();
    }

    @Test
    void method_is_case_insensitive() {
        var rule = new PermissionRegistry.Rule("GET", "/workloads/{name}", "tenant:workload:get");
        assertThat(reg.runtimeMatches(rule, "get", "/workloads/list")).isTrue();
    }

    @Test
    void different_method_does_not_match() {
        var rule = new PermissionRegistry.Rule("GET", "/workloads/{name}", "tenant:workload:get");
        assertThat(reg.runtimeMatches(rule, "POST", "/workloads/list")).isFalse();
    }

    @Test
    void ant_wildcard_matches_deeper_paths() {
        var rule = new PermissionRegistry.Rule("POST", "/role/**", "platform:role:manage");
        assertThat(reg.runtimeMatches(rule, "POST", "/role/permission/save")).isTrue();
    }

    @Test
    void star_method_is_not_a_runtime_wildcard() {
        var rule = new PermissionRegistry.Rule("*", "/anything/**", "platform:any");
        // 与 requiredCodes 一致：运行时 requiredCodes 用 equalsIgnoreCase，* 不会命中 GET
        assertThat(reg.runtimeMatches(rule, "GET", "/anything/x")).isFalse();
        assertThat(reg.requiredCodes("GET", "/anything/x")).isEmpty();
        // 而反向核账的 matches 认 *（保留原语义，不要顺手改掉）
        assertThat(reg.matches(rule, "GET", "/anything/x")).isTrue();
    }
}
