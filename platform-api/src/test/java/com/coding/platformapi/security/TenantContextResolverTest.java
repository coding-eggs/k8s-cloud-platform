package com.coding.platformapi.security;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantContextResolverTest {

    private final TenantContextResolver r = new TenantContextResolver();

    @Test
    void self_managed_must_match_token_tenant() {
        assertThat(r.requireContext("T1", "T1")).isEqualTo("T1");
        assertThatThrownBy(() -> r.requireContext("T1", "T2"))
                .isInstanceOf(CloudPlatformException.class)
                .extracting(e -> ((CloudPlatformException) e).getCode())
                .isEqualTo(EnumResponseType.TENANT_MISMATCH.getCode());
        // 自管用户省略 requestTenantId 也必须拒绝（不得静默回落到 token 租户）
        assertThatThrownBy(() -> r.requireContext("T1", null))
                .isInstanceOf(CloudPlatformException.class)
                .extracting(e -> ((CloudPlatformException) e).getCode())
                .isEqualTo(EnumResponseType.TENANT_MISMATCH.getCode());
    }

    @Test
    void delegate_requires_explicit_tenant() {
        assertThat(r.requireContext(null, "T9")).isEqualTo("T9");
        assertThatThrownBy(() -> r.requireContext(null, null))
                .isInstanceOf(CloudPlatformException.class)
                .extracting(e -> ((CloudPlatformException) e).getCode())
                .isEqualTo(EnumResponseType.TOKEN_TENANT_MISSING.getCode());
    }
}
