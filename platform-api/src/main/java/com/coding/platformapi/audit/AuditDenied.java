package com.coding.platformapi.audit;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Set;

/**
 * 403 被拒请求的权限码载体。
 *
 * <p>{@code AccessDeniedException} 本身**不带**权限码，所以由
 * {@code PermissionAuthorizationManager} 在 deny 分支把它塞进 request attribute，
 * 再由 {@code ResourceServerConfig} 的 {@code accessDeniedHandler} 读出来落审计。
 * 这样审计能回答「用户想做什么」，而不只是「调了哪个 URL」。
 *
 * <p>未认证（401）不会有这个 attribute —— 那条路径有意不记（spec §9）。
 */
public final class AuditDenied {

    /** request attribute 名 */
    public static final String ATTR_DENIED_CODES = "com.coding.audit.deniedCodes";

    private AuditDenied() {
    }

    /** 把被判的权限码塞进请求（要求集合非空；空集没有记录价值） */
    public static void mark(HttpServletRequest request, Set<String> codes) {
        if (codes != null && !codes.isEmpty()) {
            request.setAttribute(ATTR_DENIED_CODES, String.join(",", codes));
        }
    }

    /** 读出被拒的权限码；没有则 null */
    public static String codesOf(HttpServletRequest request) {
        Object v = request.getAttribute(ATTR_DENIED_CODES);
        return v == null ? null : String.valueOf(v);
    }
}
