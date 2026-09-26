package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.auth.PlatformUserMapper;
import com.coding.data.models.auth.PlatformTenant;
import com.coding.data.models.auth.PlatformUser;
import com.coding.data.models.system.TokenUserInfo;
import com.coding.platformapi.security.AuthContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * /user/me 与 /user/my-tenants 的数据源：token data claim 直通 + 按用户名反查 id 再列租户。
 */
class CurrentUserQueryTest {
    AuthContext auth = mock(AuthContext.class);
    PlatformUserMapper userMapper = mock(PlatformUserMapper.class);
    PlatformTenantMapper tenantMapper = mock(PlatformTenantMapper.class);
    CurrentUserQuery svc = new CurrentUserQuery(auth, userMapper, tenantMapper);

    @Test void me_returns_token_claim_as_is() {
        TokenUserInfo t = TokenUserInfo.builder().username("alice").build();
        when(auth.current()).thenReturn(t);
        assertThat(svc.me()).isSameAs(t);
    }

    @Test void me_without_claim_is_unlogged_in() {
        when(auth.current()).thenReturn(null);
        assertThatThrownBy(() -> svc.me())
            .isInstanceOf(CloudPlatformException.class)
            .extracting(e -> ((CloudPlatformException) e).getCode())
            .isEqualTo(EnumResponseType.USER_UN_LOGIN.getCode());
    }

    @Test void my_tenants_resolves_user_id_then_lists() {
        when(auth.current()).thenReturn(TokenUserInfo.builder().username("alice").build());
        PlatformUser u = new PlatformUser();
        u.setId("u1");
        u.setUsername("alice");
        when(userMapper.selectByUsername("alice")).thenReturn(u);
        PlatformTenant t = new PlatformTenant();
        t.setId("t1");
        when(tenantMapper.listTenantsByUser("u1")).thenReturn(List.of(t));

        assertThat(svc.myTenants()).extracting(PlatformTenant::getId).containsExactly("t1");
    }

    @Test void my_tenants_user_deleted_after_token_is_unlogged_in() {
        when(auth.current()).thenReturn(TokenUserInfo.builder().username("ghost").build());
        when(userMapper.selectByUsername("ghost")).thenReturn(null);
        assertThatThrownBy(() -> svc.myTenants())
            .isInstanceOf(CloudPlatformException.class)
            .extracting(e -> ((CloudPlatformException) e).getCode())
            .isEqualTo(EnumResponseType.USER_UN_LOGIN.getCode());
    }
}
