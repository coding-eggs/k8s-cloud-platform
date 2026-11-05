package com.coding.common.components.permission;

import com.coding.common.components.jwt.PermissionAuthorityNames;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关与 platform-api 共用的判定（spec §3、§8.3）：命中权限行（ANY-of）＞ 豁免（仅需登录）＞ 默认拒绝；匿名一律拒。
 */
class PermissionDeciderTest {

    private static final String API = PlatformServices.API;
    private static final String AUTH = PlatformServices.AUTH;

    private final PermissionRegistry registry = new PermissionRegistry(List.of(
            new PermissionRegistry.Rule(API, "GET", "/pods/**", "tenant:pod:view"),
            new PermissionRegistry.Rule(API, "GET", "/user/me", "platform:me:strict"),
            new PermissionRegistry.Rule(AUTH, "POST", "/internal/clients/list", "platform:oauth-client:view"),
            new PermissionRegistry.Rule(API, "*", "/anything/**", "platform:any")));
    private final PermissionDecider decider = new PermissionDecider(registry);

    private static List<String> holding(String... codes) {
        return java.util.Arrays.stream(codes).map(PermissionAuthorityNames::perm).toList();
    }

    @Test
    void anonymousAlwaysDenied() {
        assertThat(decider.decide(API, "GET", "/ws/pod/exec", List.of(), true))
                .isEqualTo(new PermissionDecider.Deny(Set.of()));
    }

    @Test
    void ruleHitWithCodeAllows() {
        assertThat(decider.decide(API, "GET", "/pods/a/logs", holding("tenant:pod:view"), false))
                .isInstanceOf(PermissionDecider.Allow.class);
    }

    @Test
    void ruleHitWithoutCodeDeniesEvenIfExempt() {
        // /user/me 在豁免清单里，但一旦有权限行，行优先：不持码就拒，不回落豁免
        assertThat(decider.decide(API, "GET", "/user/me", holding("x:y"), false))
                .isEqualTo(new PermissionDecider.Deny(Set.of("platform:me:strict")));
    }

    @Test
    void exemptWithoutRuleAllows() {
        assertThat(decider.decide(API, "GET", "/ws/pod/exec", List.of(), false))
                .isInstanceOf(PermissionDecider.Allow.class);
    }

    @Test
    void sameRuleOtherServiceDoesNotMatch() {
        // auth 的行不该被 api 的同名路径命中；api 侧又不豁免 → 默认拒（没有码可记）
        assertThat(decider.decide(API, "POST", "/internal/clients/list", holding("platform:oauth-client:view"), false))
                .isEqualTo(new PermissionDecider.Deny(Set.of()));
        assertThat(decider.decide(AUTH, "POST", "/internal/clients/list", holding("platform:oauth-client:view"), false))
                .isInstanceOf(PermissionDecider.Allow.class);
    }

    @Test
    void exemptListIsPlatformApiOnly() {
        assertThat(decider.decide(AUTH, "GET", "/ws/pod/exec", List.of(), false))
                .isEqualTo(new PermissionDecider.Deny(Set.of()));
    }

    @Test
    void starMethodIsNotWildcardAtRuntime() {
        assertThat(decider.decide(API, "GET", "/anything/x", holding("platform:any"), false))
                .isEqualTo(new PermissionDecider.Deny(Set.of()));
    }
}
