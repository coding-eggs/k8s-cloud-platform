package com.coding.platformapi.security;

import java.util.ArrayList;
import java.util.List;

/**
 * 交叉核账：endpoint × 权限表（Task 11）。表驱动授权（Task 10）默认拒绝，
 * 但"某个新端点既忘了加权限行、又没进豁免清单"这类漏配若不在启动期暴露，
 * 会静默变成运行时 403 —— 故启动时逐端点核对，缺口即失败。
 *
 * <p>spec §5.3 还定义了反方向核账：权限行（规则）匹配不到任何真实端点 = 幽灵行。
 * 幽灵行不产生安全缺口（无端点可命中），但会误导审计闭包与 RoleView 勾选树的计数，
 * 且往往意味着端点被删/改名而 seed 忘跟。v1 有意先保留若干"计划端点"行
 * （/tenant/update、/user/update、/role/update —— UI 与后端均未交付），
 * 因此反向核账<b>只 WARN 不阻断</b>；这三行现身的 warn 即是它们的"v1 未建"标记。
 */
public final class PermissionCrossCheck {

    /** 一个已注册的 handler 端点：HTTP 方法名（无方法限定为 "*"）+ URL pattern。 */
    public record Endpoint(String method, String pattern) {}

    private PermissionCrossCheck() {}

    /** 返回未被任何权限行、且非豁免的 endpoint（启动应失败）。 */
    public static List<Endpoint> uncoveredEndpoints(List<Endpoint> endpoints, PermissionRegistry reg) {
        return endpoints.stream()
                .filter(e -> !ExemptPaths.isExempt(e.pattern()))
                .filter(e -> reg.requiredCodes(e.method(), e.pattern()).isEmpty())
                .toList();
    }

    /** §5.3 反向：返回匹配不到任何真实端点的规则（幽灵行）；只作 WARN 依据，绝不抛错。 */
    public static List<PermissionRegistry.Rule> ghostRules(List<Endpoint> endpoints, PermissionRegistry reg) {
        List<PermissionRegistry.Rule> ghosts = new ArrayList<>();
        for (PermissionRegistry.Rule rule : reg.rules()) {
            boolean hits = endpoints.stream().anyMatch(e -> reg.matches(rule, e.method(), e.pattern()));
            if (!hits) {
                ghosts.add(rule);
            }
        }
        return ghosts;
    }
}
