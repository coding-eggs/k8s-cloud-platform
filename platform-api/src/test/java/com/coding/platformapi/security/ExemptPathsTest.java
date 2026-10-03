package com.coding.platformapi.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExemptPathsTest {

    @Test
    void exempt_paths_match() {
        assertThat(ExemptPaths.isExempt("/user/me")).isTrue();
        assertThat(ExemptPaths.isExempt("/callback")).isTrue();
        assertThat(ExemptPaths.isExempt("/actuator/health")).isTrue();
        assertThat(ExemptPaths.isExempt("/v3/api-docs/default")).isTrue();
        // pod-exec WebSocket 转发路径（controller 裁定豁免）
        assertThat(ExemptPaths.isExempt("/ws/exec/pod-1")).isTrue();
    }

    /**
     * springdoc（knife4j 引入）是真实 @RestController，其 handler 会被 Task 11 交叉校验枚举。
     * 这三条 pattern 不被 /v3/api-docs/** 、/swagger-ui/** 匹配（AntPathMatcher：'.' 不是 '/'），
     * 一旦丢失，启动即被交叉校验 brick —— 故钉死。
     */
    @Test
    void springdoc_handler_patterns_are_exempt() {
        assertThat(ExemptPaths.isExempt("/v3/api-docs.yaml")).isTrue();
        assertThat(ExemptPaths.isExempt("/v3/api-docs.yaml/{group}")).isTrue();
        assertThat(ExemptPaths.isExempt("/swagger-ui.html")).isTrue();
        assertThat(ExemptPaths.isExempt("/v3/api-docs/swagger-config")).isTrue();
        assertThat(ExemptPaths.isExempt("/error")).isTrue();
    }

    @Test
    void business_paths_not_exempt() {
        assertThat(ExemptPaths.isExempt("/tenant/create")).isFalse();
        assertThat(ExemptPaths.isExempt("/tenant/member/add")).isFalse();
        assertThat(ExemptPaths.isExempt("/user/list")).isFalse();
    }

    @Test
    void resource_api_paths_no_longer_exempt() {
        // /resource/** 整体豁免已移除（V2026_09_29_1）：仅 context + prom discovery 四端点保留豁免
        assertThat(ExemptPaths.isExempt("/resource/context")).isTrue();
        assertThat(ExemptPaths.isExempt("/resource/servicemonitors/relabel-labels")).isTrue();
        assertThat(ExemptPaths.isExempt("/resource/servicemonitors/metric-names")).isTrue();
        assertThat(ExemptPaths.isExempt("/resource/podmonitors/relabel-labels")).isTrue();
        assertThat(ExemptPaths.isExempt("/resource/podmonitors/metric-names")).isTrue();
        assertThat(ExemptPaths.isExempt("/resource/workloads/list")).isFalse();
        assertThat(ExemptPaths.isExempt("/resource/pods/nginx-1/logs")).isFalse();
        assertThat(ExemptPaths.isExempt("/resource/nodes/cordon")).isFalse();
        assertThat(ExemptPaths.isExempt("/resource/secrets/{name}")).isFalse();
    }

    @Test
    void namespace_metrics_read_endpoints_are_exempt() {
        // 命名空间概览 4 图只读端点豁免（任意已登录用户可看）；其余 /namespace/** 仍走权限表
        assertThat(ExemptPaths.isExempt("/namespace/metrics/cpu")).isTrue();
        assertThat(ExemptPaths.isExempt("/namespace/metrics/memory")).isTrue();
        assertThat(ExemptPaths.isExempt("/namespace/metrics/network")).isTrue();
        assertThat(ExemptPaths.isExempt("/namespace/metrics/disk")).isTrue();
        assertThat(ExemptPaths.isExempt("/namespace/list")).isFalse();
        assertThat(ExemptPaths.isExempt("/namespace/delete")).isFalse();
    }
}
