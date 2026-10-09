package com.coding.platformapi.services;

import com.coding.common.components.jwt.JwtPermissionResolver;
import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.auth.PlatformUserMapper;
import com.coding.data.models.auth.PlatformTenant;
import com.coding.data.models.auth.PlatformUser;
import com.coding.data.models.system.TokenUserInfo;
import com.coding.data.models.system.UserTenantInfo;
import com.coding.platformapi.security.AuthContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * /user/me 与 /user/my-tenants 的数据源：token data claim（身份/角色/租户）+ **现算**权限闭包。
 */
class CurrentUserQueryTest {
    AuthContext auth = mock(AuthContext.class);
    PlatformUserMapper userMapper = mock(PlatformUserMapper.class);
    PlatformTenantMapper tenantMapper = mock(PlatformTenantMapper.class);
    JwtPermissionResolver permissionResolver = mock(JwtPermissionResolver.class);
    CurrentUserQuery svc = new CurrentUserQuery(auth, userMapper, tenantMapper, permissionResolver);

    /** 权限码自 2026-10-09 起不随 token 下发 → /user/me 必须现算，且租户上下文取自 token 帽。 */
    @Test void me_fills_permissions_from_resolver_using_token_hat() {
        var hat = new UserTenantInfo();
        hat.setTenantId("t1");
        TokenUserInfo t = TokenUserInfo.builder().username("alice").tenantInfo(hat).build();
        when(auth.current()).thenReturn(t);
        when(permissionResolver.resolve("alice", "t1")).thenReturn(List.of("tenant:workload:list"));

        assertThat(svc.me().getPermissions()).containsExactly("tenant:workload:list");
    }

    /** 平台视图（base token 无 tenantInfo）→ 解析器收到 null，只算平台族。 */
    @Test void me_passes_null_tenant_for_base_token() {
        when(auth.current()).thenReturn(TokenUserInfo.builder().username("admin").build());
        when(permissionResolver.resolve("admin", null)).thenReturn(List.of("platform:role:read"));

        assertThat(svc.me().getPermissions()).containsExactly("platform:role:read");
    }

    @Test void me_without_claim_is_unlogged_in() {
        when(auth.current()).thenReturn(null);
        assertThatThrownBy(() -> svc.me())
            .isInstanceOf(CloudPlatformException.class)
            .extracting(e -> ((CloudPlatformException) e).getCode())
            .isEqualTo(EnumResponseType.USER_UN_LOGIN.getCode());
    }

    /** my-tenants 不需要权限闭包 → 不该触发解析（省一次缓存查询/查库）。 */
    @Test void my_tenants_does_not_resolve_permissions() {
        when(auth.current()).thenReturn(TokenUserInfo.builder().username("alice").build());
        PlatformUser u = new PlatformUser();
        u.setId("u1");
        when(userMapper.selectByUsername("alice")).thenReturn(u);
        when(tenantMapper.listTenantsByUser("u1")).thenReturn(List.of());

        svc.myTenants();

        verifyNoInteractions(permissionResolver);
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
