package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.data.mapper.auth.PlatformRoleMapper;
import com.coding.data.mapper.auth.PlatformUserMapper;
import com.coding.data.mapper.auth.PlatformUserTenantMapper;
import com.coding.data.mapper.auth.PlatformUserTenantRoleMapper;
import com.coding.data.models.auth.PlatformRole;
import com.coding.data.models.auth.PlatformUser;
import com.coding.data.models.auth.PlatformUserTenant;
import com.coding.data.models.auth.PlatformUserTenantRole;
import com.coding.platformapi.models.TenantMemberView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 不变量 2（先有成员再授角色）与不变量 3（不能移空最后一个租户管理员）的锁测试，
 * 外加 owner 标记（持有 tenant-admin 角色 → owner=true）与成员移除级联。
 */
class TenantMemberServiceTest {
    PlatformUserTenantMapper utm = mock(PlatformUserTenantMapper.class);
    PlatformUserTenantRoleMapper utr = mock(PlatformUserTenantRoleMapper.class);
    PlatformRoleMapper roleMapper = mock(PlatformRoleMapper.class);
    PlatformUserMapper userMapper = mock(PlatformUserMapper.class);

    @Test void add_member_without_tenant_row_rejected() {
        when(utm.exists("u1", "t1")).thenReturn(false);
        var svc = memberSvc();
        assertThatThrownBy(() -> svc.grantRole("t1", "u1", "roleX"))
            .isInstanceOf(CloudPlatformException.class)
            .extracting(e -> ((CloudPlatformException) e).getCode())
            .isEqualTo(EnumResponseType.TENANT_MEMBER_NOT_FOUND.getCode());
    }

    @Test void cannot_revoke_last_admin() {
        var svc = memberSvc();
        when(roleMapper.selectByCode("tenant-admin")).thenReturn(adminRole());
        when(utr.countByTenantAndRoleForUpdate("t1", "adminRole")).thenReturn(1);
        assertThatThrownBy(() -> svc.revokeRole("t1", "u1", "adminRole"))
            .isInstanceOf(CloudPlatformException.class)
            .extracting(e -> ((CloudPlatformException) e).getCode())
            .isEqualTo(EnumResponseType.TENANT_ADMIN_REQUIRED.getCode());
    }

    @Test void revoke_admin_with_second_admin_proceeds() {
        var svc = memberSvc();
        when(roleMapper.selectByCode("tenant-admin")).thenReturn(adminRole());
        when(utr.countByTenantAndRoleForUpdate("t1", "adminRole")).thenReturn(2);
        assertThatCode(() -> svc.revokeRole("t1", "u1", "adminRole")).doesNotThrowAnyException();
        verify(utr).delete("u1", "t1", "adminRole");
    }

    @Test void revoke_non_admin_skips_admin_check() {
        var svc = memberSvc();
        when(roleMapper.selectByCode("tenant-admin")).thenReturn(adminRole());
        assertThatCode(() -> svc.revokeRole("t1", "u1", "roleX")).doesNotThrowAnyException();
        verify(utr, never()).countByTenantAndRoleForUpdate(any(), any());
        verify(utr).delete("u1", "t1", "roleX");
    }

    @Test void add_member_is_idempotent_when_existing() {
        when(utm.exists("u1", "t1")).thenReturn(true);
        assertThatCode(() -> memberSvc().addMember("t1", "u1")).doesNotThrowAnyException();
        verify(utm, never()).insert(any());
    }

    @Test void add_member_inserts_when_absent() {
        when(utm.exists("u1", "t1")).thenReturn(false);
        memberSvc().addMember("t1", "u1");
        verify(utm).insert(any(PlatformUserTenant.class));
    }

    @Test void grant_duplicate_role_is_idempotent() {
        when(utm.exists("u1", "t1")).thenReturn(true);
        when(roleMapper.selectByPrimaryKey("roleX")).thenReturn(tenantRole("roleX"));
        when(utr.selectByTenantAndUser("t1", "u1")).thenReturn(List.of(utrRow("u1", "roleX")));
        assertThatCode(() -> memberSvc().grantRole("t1", "u1", "roleX")).doesNotThrowAnyException();
        verify(utr, never()).insert(any());
    }

    // ---- CRITICAL 1(a)：grantRole 角色域/存在性/启用校验，阻断租户提权到平台角色 ----

    @Test void grant_platform_scoped_role_rejected() {
        when(utm.exists("u1", "t1")).thenReturn(true);
        PlatformRole plat = new PlatformRole();
        plat.setId("builtin_role_admin");
        plat.setCode("admin");
        plat.setScope("PLATFORM");
        plat.setStatus((byte) 1);
        when(roleMapper.selectByPrimaryKey("builtin_role_admin")).thenReturn(plat);
        assertThatThrownBy(() -> memberSvc().grantRole("t1", "u1", "builtin_role_admin"))
            .isInstanceOf(CloudPlatformException.class);
        verify(utr, never()).insert(any());
    }

    @Test void grant_nonexistent_role_rejected() {
        when(utm.exists("u1", "t1")).thenReturn(true);
        when(roleMapper.selectByPrimaryKey("ghost")).thenReturn(null);
        assertThatThrownBy(() -> memberSvc().grantRole("t1", "u1", "ghost"))
            .isInstanceOf(CloudPlatformException.class);
        verify(utr, never()).insert(any());
    }

    @Test void grant_disabled_role_rejected() {
        when(utm.exists("u1", "t1")).thenReturn(true);
        PlatformRole off = tenantRole("roleOff");
        off.setStatus((byte) 0);
        when(roleMapper.selectByPrimaryKey("roleOff")).thenReturn(off);
        assertThatThrownBy(() -> memberSvc().grantRole("t1", "u1", "roleOff"))
            .isInstanceOf(CloudPlatformException.class);
        verify(utr, never()).insert(any());
    }

    @Test void cannot_remove_last_admin() {
        var svc = memberSvc();
        when(roleMapper.selectByCode("tenant-admin")).thenReturn(adminRole());
        when(utr.selectByTenantAndUser("t1", "u1")).thenReturn(List.of(utrRow("u1", "adminRole")));
        when(utr.countByTenantAndRoleForUpdate("t1", "adminRole")).thenReturn(1);
        assertThatThrownBy(() -> svc.removeMember("t1", "u1"))
            .isInstanceOf(CloudPlatformException.class)
            .extracting(e -> ((CloudPlatformException) e).getCode())
            .isEqualTo(EnumResponseType.TENANT_ADMIN_REQUIRED.getCode());
        verify(utm, never()).deleteByUserAndTenant(any(), any());
    }

    @Test void remove_member_cascades_roles() {
        var svc = memberSvc();
        when(roleMapper.selectByCode("tenant-admin")).thenReturn(adminRole());
        when(utr.selectByTenantAndUser("t1", "u1")).thenReturn(List.of(utrRow("u1", "roleX")));
        svc.removeMember("t1", "u1");
        verify(utr).deleteByUserAndTenant("u1", "t1");
        verify(utm).deleteByUserAndTenant("u1", "t1");
    }

    @Test void list_members_flags_owner_and_roles() {
        var svc = memberSvc();
        when(roleMapper.selectByCode("tenant-admin")).thenReturn(adminRole());
        PlatformUserTenant m1 = utRow("u1");
        PlatformUserTenant m2 = utRow("u2");
        when(utm.selectByTenantId("t1")).thenReturn(List.of(m1, m2));
        when(utr.selectByTenantAndUser("t1", "u1")).thenReturn(List.of(utrRow("u1", "adminRole"), utrRow("u1", "roleX")));
        when(utr.selectByTenantAndUser("t1", "u2")).thenReturn(List.of(utrRow("u2", "roleX")));
        when(userMapper.selectByPrimaryKey("u1")).thenReturn(user("u1", "alice"));
        when(userMapper.selectByPrimaryKey("u2")).thenReturn(user("u2", "bob"));

        List<TenantMemberView> views = svc.listMembers("t1");

        assertThat(views).hasSize(2);
        TenantMemberView v1 = views.get(0);
        assertThat(v1.isOwner()).isTrue();
        assertThat(v1.getRoleIds()).containsExactly("adminRole", "roleX");
        assertThat(v1.getUsername()).isEqualTo("alice");
        TenantMemberView v2 = views.get(1);
        assertThat(v2.isOwner()).isFalse();
        assertThat(v2.getRoleIds()).containsExactly("roleX");
        assertThat(v2.getUsername()).isEqualTo("bob");
    }

    @Test void missing_builtin_admin_role_fails_fast() {
        var svc = memberSvc();
        when(utm.exists("u1", "t1")).thenReturn(true);
        when(roleMapper.selectByCode("tenant-admin")).thenReturn(null);
        // grantRole 本身不需要 admin 角色，但 revoke/list 需要 → 用 revoke 验证 seed 缺失快速失败
        assertThatThrownBy(() -> svc.revokeRole("t1", "u1", "roleX"))
            .isInstanceOf(IllegalStateException.class);
    }

    private com.coding.data.models.auth.PlatformRole adminRole() {
        var r = new com.coding.data.models.auth.PlatformRole();
        r.setId("adminRole");
        r.setCode("tenant-admin");
        r.setScope("TENANT");
        r.setBuiltIn((byte) 1);
        return r;
    }

    private static PlatformRole tenantRole(String id) {
        var r = new PlatformRole();
        r.setId(id);
        r.setCode("custom-" + id);
        r.setScope("TENANT");
        r.setStatus((byte) 1);
        return r;
    }

    private static PlatformUserTenant utRow(String userId) {
        var r = new PlatformUserTenant();
        r.setUserId(userId);
        r.setTenantId("t1");
        return r;
    }

    private static PlatformUserTenantRole utrRow(String userId, String roleId) {
        var r = new PlatformUserTenantRole();
        r.setUserId(userId);
        r.setTenantId("t1");
        r.setRoleId(roleId);
        return r;
    }

    private static PlatformUser user(String id, String username) {
        var u = new PlatformUser();
        u.setId(id);
        u.setUsername(username);
        u.setDisplayName(username.toUpperCase());
        return u;
    }

    private TenantMemberService memberSvc() {
        return new TenantMemberService(utm, utr, roleMapper, userMapper);
    }
}
