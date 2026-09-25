package com.coding.platformapi.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExemptPathsTest {

    @Test
    void exempt_paths_match() {
        assertThat(ExemptPaths.isExempt("/resource/deployment/list")).isTrue();
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
}
