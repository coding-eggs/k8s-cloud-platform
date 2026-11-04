package com.coding.common.components.permission;

import com.coding.common.components.jwt.PermissionAuthorityNames;

import java.util.Collection;
import java.util.Set;

/**
 * 接口级判定（spec §5.1 / 网关 spec §8.3），网关与 platform-api 共用的**纯逻辑**（不碰 servlet / reactive）：
 * 命中权限行（ANY-of PERM）＞ 豁免路径（仅需登录）＞ 默认拒绝；匿名一律拒。
 *
 * <p>为什么匿名要单独传：AnonymousAuthenticationToken.isAuthenticated() 恒为 true，调用方必须显式判掉，
 * 否则未登录可命中豁免路径（豁免 = 仅 authenticated）。
 */
public final class PermissionDecider {

    /** 判定结果。 */
    public sealed interface Decision permits Allow, Deny {
    }

    public record Allow() implements Decision {
    }

    /**
     * 拒绝。{@code requiredCodes} 非空 = 命中了权限行但不持码（审计要记这些码）；
     * 空 = 匿名，或「无权限行且不豁免」（启动交叉校验本不该让后者发生，没有码可记）。
     */
    public record Deny(Set<String> requiredCodes) implements Decision {
    }

    private final PermissionRegistry registry;

    public PermissionDecider(PermissionRegistry registry) {
        this.registry = registry;
    }

    /**
     * @param authorities 调用方持有的 authority 名（含 {@code PERM:<code>}）
     * @param anonymous   未认证 / 匿名
     */
    public Decision decide(String service, String method, String path,
                           Collection<String> authorities, boolean anonymous) {
        if (anonymous) {
            return new Deny(Set.of());
        }
        // 权限行命中优先于豁免：行可收紧豁免路径；命中行但无码 → 直接拒，不回落豁免
        Set<String> required = registry.requiredCodes(service, method, path).orElse(null);
        if (required != null) {
            for (String code : required) {
                if (authorities.contains(PermissionAuthorityNames.perm(code))) {
                    return new Allow();
                }
            }
            return new Deny(required);
        }
        if (ExemptPaths.isExempt(service, path)) {
            return new Allow();
        }
        return new Deny(Set.of());
    }
}
