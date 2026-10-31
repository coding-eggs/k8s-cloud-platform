package com.coding.auth.service;

import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.auth.PlatformUserMapper;
import com.coding.data.mapper.auth.PlatformUserRoleMapper;
import com.coding.data.models.auth.PlatformTenant;
import com.coding.data.models.auth.PlatformUser;
import com.coding.data.models.system.UserTenantInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 切租户上下文的两条路（spec 2026-10-31 §3.2）：
 * <ol>
 *   <li>成员 —— 原路，查 {@code platform_user_tenant}；</li>
 *   <li>平台侧身份 —— 不靠成员关系，目标是**启用**租户即可。</li>
 * </ol>
 * 与 platform-api 的 {@code CurrentUserQuery.myTenants()} 是同一套判据的两半：那边列哪些，
 * 这边能不能真切。只改一半的话，切换器里点得动、切过去 invalid_grant。
 */
class TokenExtrasServiceTest {

    private final PlatformUserRoleMapper userRoleMapper = mock(PlatformUserRoleMapper.class);
    private final PlatformTenantMapper tenantMapper = mock(PlatformTenantMapper.class);
    private final PlatformUserMapper userMapper = mock(PlatformUserMapper.class);

    private final TokenExtrasService svc =
            new TokenExtrasService(userRoleMapper, tenantMapper, userMapper);

    private PlatformTenant tenant(String id, byte status) {
        PlatformTenant t = new PlatformTenant();
        t.setId(id);
        t.setName("name-" + id);
        t.setServiceAccount("sa-" + id);
        t.setStatus(status);
        return t;
    }

    @Test
    void a_member_resolves_by_membership() {
        UserTenantInfo byMembership = new UserTenantInfo();
        byMembership.setTenantId("t1");
        when(tenantMapper.selectTenantByUser("alice", "t1")).thenReturn(byMembership);

        assertThat(svc.resolveTenant("alice", "t1")).isSameAs(byMembership);
        verify(userMapper, never()).selectByUsername(anyString());
    }

    @Test
    void a_platform_admin_enters_any_enabled_tenant_without_membership() {
        when(tenantMapper.selectTenantByUser("admin", "t9")).thenReturn(null);
        when(userMapper.selectByUsername("admin")).thenReturn(user("u1"));
        when(userRoleMapper.selectRoleCodesByUser("u1")).thenReturn(List.of("admin"));
        when(tenantMapper.selectByPrimaryKey("t9")).thenReturn(tenant("t9", (byte) 1));

        UserTenantInfo info = svc.resolveTenant("admin", "t9");

        assertThat(info).isNotNull();
        assertThat(info.getTenantId()).isEqualTo("t9");
        assertThat(info.getTenantName()).isEqualTo("name-t9");
        assertThat(info.getServiceAccount()).as("tenant client 要用它定位 tn-<sa>").isEqualTo("sa-t9");
    }

    @Test
    void a_platform_admin_cannot_enter_a_disabled_tenant() {
        when(tenantMapper.selectTenantByUser("admin", "t9")).thenReturn(null);
        when(userMapper.selectByUsername("admin")).thenReturn(user("u1"));
        when(userRoleMapper.selectRoleCodesByUser("u1")).thenReturn(List.of("admin"));
        when(tenantMapper.selectByPrimaryKey("t9")).thenReturn(tenant("t9", (byte) 0));

        assertThat(svc.resolveTenant("admin", "t9")).isNull();
    }

    @Test
    void a_plain_user_without_membership_is_refused() {
        when(tenantMapper.selectTenantByUser("bob", "t9")).thenReturn(null);
        when(userMapper.selectByUsername("bob")).thenReturn(user("u2"));
        when(userRoleMapper.selectRoleCodesByUser("u2")).thenReturn(List.of());

        assertThat(svc.resolveTenant("bob", "t9")).isNull();
    }

    @Test
    void a_blank_tenant_stays_null() {
        assertThat(svc.resolveTenant("alice", " ")).isNull();
        verify(tenantMapper, never()).selectTenantByUser(anyString(), anyString());
    }

    private PlatformUser user(String id) {
        PlatformUser u = new PlatformUser();
        u.setId(id);
        return u;
    }
}
