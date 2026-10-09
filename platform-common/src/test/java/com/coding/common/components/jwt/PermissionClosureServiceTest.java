package com.coding.common.components.jwt;

import com.coding.data.mapper.auth.PlatformPermissionMapper;
import com.coding.data.mapper.auth.PlatformUserMapper;
import com.coding.data.mapper.auth.PlatformUserRoleMapper;
import com.coding.data.mapper.auth.PlatformUserTenantRoleMapper;
import com.coding.data.models.auth.PlatformUser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 权限闭包语义的**锚点**：这条语义原先只活在 platform-auth 的 TokenExtrasService 里（无测试），
 * 2026-10-09 搬到本类后由 platform-auth（回滚开关）与 platform-api（常态解析）共用。
 * 语义一旦漂移就是静默过度授权，故逐条钉住。
 */
class PermissionClosureServiceTest {

    PlatformUserMapper userMapper = mock(PlatformUserMapper.class);
    PlatformUserRoleMapper userRoleMapper = mock(PlatformUserRoleMapper.class);
    PlatformUserTenantRoleMapper userTenantRoleMapper = mock(PlatformUserTenantRoleMapper.class);
    PlatformPermissionMapper permissionMapper = mock(PlatformPermissionMapper.class);

    PermissionClosureService svc =
            new PermissionClosureService(userMapper, userRoleMapper, userTenantRoleMapper, permissionMapper);

    private void userExists(String username, String id) {
        PlatformUser u = new PlatformUser();
        u.setId(id);
        u.setUsername(username);
        when(userMapper.selectByUsername(username)).thenReturn(u);
    }

    /** 平台视图（base token）：只算平台族，**不碰**租户内的角色表。 */
    @Test
    void null_tenant_means_platform_family_only() {
        userExists("admin", "u1");
        when(userRoleMapper.selectRoleIdsByUser("u1")).thenReturn(List.of("r_plat"));
        when(permissionMapper.selectPermissionCodesByRoleIds(List.of("r_plat")))
                .thenReturn(List.of("platform:cluster:manage", "platform:role:read"));

        assertThat(svc.permissions("admin", null))
                .containsExactly("platform:cluster:manage", "platform:role:read");
        verifyNoInteractions(userTenantRoleMapper);
    }

    /** 带租户上下文：平台族 ∪ 该租户内角色，去重保序（平台族在前）。 */
    @Test
    void tenant_context_merges_both_families() {
        userExists("alice", "u2");
        when(userRoleMapper.selectRoleIdsByUser("u2")).thenReturn(List.of("r_plat"));
        when(permissionMapper.selectPermissionCodesByRoleIds(List.of("r_plat")))
                .thenReturn(List.of("platform:role:read"));
        when(userTenantRoleMapper.selectRoleIdsByUserAndTenant("u2", "t1")).thenReturn(List.of("r_tenant"));
        when(permissionMapper.selectPermissionCodesByRoleIds(List.of("r_tenant")))
                .thenReturn(List.of("tenant:workload:list", "platform:role:read"));

        assertThat(svc.permissions("alice", "t1"))
                .containsExactly("platform:role:read", "tenant:workload:list");
    }

    /** 空字符串租户与 null 同义（别把 "" 当成一个真租户去查）。 */
    @Test
    void blank_tenant_is_same_as_null() {
        userExists("alice", "u2");
        when(userRoleMapper.selectRoleIdsByUser("u2")).thenReturn(List.of());

        assertThat(svc.permissions("alice", "  ")).isEmpty();
        verifyNoInteractions(userTenantRoleMapper);
    }

    /** 没有任何角色 → 空闭包，且不得用空 IN() 去查权限表（mapper 层非法）。 */
    @Test
    void no_roles_yields_empty_without_querying_permissions() {
        userExists("nobody", "u3");
        when(userRoleMapper.selectRoleIdsByUser("u3")).thenReturn(List.of());

        assertThat(svc.permissions("nobody", null)).isEmpty();
        verify(permissionMapper, never()).selectPermissionCodesByRoleIds(any());
    }

    /** 用户不存在（已删/脏 token）→ 空闭包，fail-closed 且不抛。 */
    @Test
    void unknown_user_yields_empty() {
        when(userMapper.selectByUsername(anyString())).thenReturn(null);

        assertThat(svc.permissions("ghost", "t1")).isEmpty();
        verifyNoInteractions(userRoleMapper, userTenantRoleMapper, permissionMapper);
    }

    /** 空白用户名 → 空闭包，不查库。 */
    @Test
    void blank_username_yields_empty() {
        assertThat(svc.permissions("  ", "t1")).isEmpty();
        assertThat(svc.permissions(null, null)).isEmpty();
        verifyNoInteractions(userMapper, userRoleMapper, userTenantRoleMapper, permissionMapper);
    }
}
