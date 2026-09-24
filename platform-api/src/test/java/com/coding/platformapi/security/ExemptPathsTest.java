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

    @Test
    void business_paths_not_exempt() {
        assertThat(ExemptPaths.isExempt("/tenant/create")).isFalse();
        assertThat(ExemptPaths.isExempt("/tenant/member/add")).isFalse();
        assertThat(ExemptPaths.isExempt("/user/list")).isFalse();
    }
}
