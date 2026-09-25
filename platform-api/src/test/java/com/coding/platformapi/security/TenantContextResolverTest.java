package com.coding.platformapi.security;

import com.coding.common.exception.CloudPlatformException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantContextResolverTest {

    private final TenantContextResolver r = new TenantContextResolver();

    @Test
    void self_managed_must_match_token_tenant() {
        assertThat(r.requireContext("T1", "T1")).isEqualTo("T1");
        assertThatThrownBy(() -> r.requireContext("T1", "T2"))
                .isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void delegate_requires_explicit_tenant() {
        assertThat(r.requireContext(null, "T9")).isEqualTo("T9");
        assertThatThrownBy(() -> r.requireContext(null, null))
                .isInstanceOf(CloudPlatformException.class);
    }
}
