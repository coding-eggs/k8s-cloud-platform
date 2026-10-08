package com.coding.platformapi.security;

import org.springframework.util.AntPathMatcher;

/**
 * 豁免权限校验的路径清单（Ant 风格）。
 * /ws/** 为 pod-exec WebSocket 转发路径（handler 注册在 RequestMapping 之外），有意豁免。
 * springdoc 文档端点：knife4j 引入的 springdoc 是真实 @RestController，会出现在
 * RequestMappingHandlerMapping 里被 Task 11 交叉校验枚举；/v3/api-docs.yaml、
 * /v3/api-docs.yaml/{group}、/swagger-ui.html 是字面路径/独立 pattern，
 * 不被 /v3/api-docs/** 、/swagger-ui/** 匹配（AntPathMatcher 实测），不补则启动必 brick。
 * /resource/** 整体豁免已于 2026-09-29 移除（资源域细粒度权限，V2026_09_29_1）：
 * 仅保留 /resource/context 与 SM/PM 的 prom discovery 四端点。
 */
public final class ExemptPaths {

    private static final String[] PREFIXES = {
            "/context",
            "/servicemonitors/relabel-labels",
            "/servicemonitors/metric-names",
            "/podmonitors/relabel-labels",
            "/podmonitors/metric-names",
            // 命名空间概览的 4 个只读 metrics 端点（cpu/memory/network/disk）：集群级、跨该 ns 全部 pod 聚合，
            // 纯只读展示，任意已登录用户可看（与「命名空间读全开放」一致），故豁免权限行；其余 /namespace/** 仍走权限表。
            "/namespace/metrics/**",
            "/user/me", "/user/my-tenants", "/callback", "/error",
            "/actuator/**", "/doc.html", "/swagger-ui/**", "/swagger-ui.html",
            "/v3/api-docs/**", "/v3/api-docs.yaml", "/v3/api-docs.yaml/**",
            "/favicon.ico",
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
