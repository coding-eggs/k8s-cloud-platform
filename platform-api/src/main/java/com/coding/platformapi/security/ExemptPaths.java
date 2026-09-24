package com.coding.platformapi.security;

import org.springframework.util.AntPathMatcher;

/**
 * 豁免权限校验的路径清单（Ant 风格）。
 * /ws/** 为 pod-exec WebSocket 转发路径（handler 注册在 RequestMapping 之外），有意豁免。
 */
public final class ExemptPaths {

    private static final String[] PREFIXES = {
            "/resource/**", "/user/me", "/user/my-tenants", "/callback", "/error",
            "/actuator/**", "/doc.html", "/swagger-ui/**", "/v3/api-docs/**", "/favicon.ico",
            "/ws/**"
    };

    private static final AntPathMatcher M = new AntPathMatcher();

    private ExemptPaths() {}

    public static boolean isExempt(String path) {
        for (String p : PREFIXES) {
            if (M.match(p, path)) {
                return true;
            }
        }
        return false;
    }
}
