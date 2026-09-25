package com.coding.platformapi.security;

import java.util.List;

/**
 * 交叉核账：endpoint × 权限表（Task 11）。表驱动授权（Task 10）默认拒绝，
 * 但"某个新端点既忘了加权限行、又没进豁免清单"这类漏配若不在启动期暴露，
 * 会静默变成运行时 403 —— 故启动时逐端点核对，缺口即失败。
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
}
