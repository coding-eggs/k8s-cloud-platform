package com.coding.platformapi.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PermissionCrossCheckTest {

    @Test
    void flagged_uncovered_bare_endpoint() {
        PermissionRegistry reg = mock(PermissionRegistry.class);
        when(reg.requiredCodes(anyString(), anyString())).thenReturn(Optional.empty());
        when(reg.requiredCodes("POST", "/tenant/create"))
                .thenReturn(Optional.of(Set.of("platform:tenant:provision")));

        var gaps = PermissionCrossCheck.uncoveredEndpoints(
                List.of(new PermissionCrossCheck.Endpoint("POST", "/tenant/create"),
                        new PermissionCrossCheck.Endpoint("POST", "/user/create")), reg);

        assertThat(gaps).extracting(PermissionCrossCheck.Endpoint::pattern)
                .containsExactly("/user/create");
    }

    @Test
    void exempt_endpoints_not_flagged_even_without_rule() {
        PermissionRegistry reg = mock(PermissionRegistry.class);
        when(reg.requiredCodes(anyString(), anyString())).thenReturn(Optional.empty());

        var gaps = PermissionCrossCheck.uncoveredEndpoints(
                List.of(new PermissionCrossCheck.Endpoint("GET", "/resource/pods/foo")), reg);

        assertThat(gaps).isEmpty();
    }
}
