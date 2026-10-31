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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 不变量 2（先有成员再授角色）、不变量 3 的撤销（2026-10-31 起零管理员合法）、
 * 加入成员默认授予「租户成员」、owner 标记（持有 tenant-admin → owner=true）与成员移除级联。
 */
class TenantMemberServiceTest {
    PlatformUserTenantMapper utm = mock(PlatformUserTenantMapper.class);
    PlatformUserTenantRoleMapper utr = mock(PlatformUserTenantRoleMapper.class);
    PlatformRoleMapper roleMapper = mock(PlatformRoleMapper.class);
    PlatformUserMapper userMapper = mock(PlatformUserMapper.class);
    /** 平台角色查询：默认返回 null（= 非平台用户），需要时按用例打桩 */
    com.coding.data.mapper.auth.PlatformUserRoleMapper userRoleMapper =
            mock(com.coding.data.mapper.auth.PlatformUserRoleMapper.class);

    @Test void add_member_without_tenant_row_rejected() {
        when(utm.exists("u1", "t1")).thenReturn(false);
        var svc = memberSvc();
        assertThatThrownBy(() -> svc.grantRole("t1", "u1", "roleX"))
            .isInstanceOf(CloudPlatformException.class)
            .extracting(e -> ((CloudPlatformException) e).getCode())
            .isEqualTo(EnumResponseType.TENANT_MEMBER_NOT_FOUND.getCode());
    }

    @Test void revoking_the_last_admin_is_allowed() {
        // 2026-10-31 撤销不变量 3：零管理员是合法状态（建租户时 owner 可选、平台可全权代管），
        // 只在移除侧拦会自相矛盾，而且只能"先加一个再删"。提醒改由前端弹确认。
        var svc = memberSvc();
        assertThatCode(() -> svc.revokeRole("t1", "u1", "adminRole")).doesNotThrowAnyException();
        verify(utr).delete("u1", "t1", "adminRole");
    }

    @Test void add_member_is_idempotent_when_existing() {
        when(utm.exists("u1", "t1")).thenReturn(true);
        assertThatCode(() -> memberSvc().addMember("t1", "u1")).doesNotThrowAnyException();
        verify(utm, never()).insert(any());
        // 已是成员时不补授默认角色 —— 否则「添加」会变成「重置角色」，
        // 把操作者后来回收过的角色悄悄加回来
        verify(utr, never()).insert(any());
    }

    @Test void add_member_inserts_when_absent() {
        // 第一次 exists=false（→ 插成员行），第二次 exists=true（grantRole 的前置校验看到刚插的行）
        when(utm.exists("u1", "t1")).thenReturn(false, true);
        when(roleMapper.selectByCode("tenant-member")).thenReturn(memberRole());
        when(roleMapper.selectByPrimaryKey("memberRole")).thenReturn(memberRole());
        when(utr.selectByTenantAndUser("t1", "u1")).thenReturn(List.of());
        memberSvc().addMember("t1", "u1");
        verify(utm).insert(any(PlatformUserTenant.class));
    }

    @Test void add_member_grants_tenant_member_by_default() {
        // 加入即具备租户内只读边界，不必再多点一次「授予」
        when(utm.exists("u1", "t1")).thenReturn(false, true);
        when(roleMapper.selectByCode("tenant-member")).thenReturn(memberRole());
        when(roleMapper.selectByPrimaryKey("memberRole")).thenReturn(memberRole());
        when(utr.selectByTenantAndUser("t1", "u1")).thenReturn(List.of());

        memberSvc().addMember("t1", "u1");

        verify(utr).insert(argThat((PlatformUserTenantRole r) -> "memberRole".equals(r.getRoleId())));
    }

    @Test void missing_builtin_member_role_fails_fast() {
        when(utm.exists("u1", "t1")).thenReturn(false);
        when(roleMapper.selectByCode("tenant-member")).thenReturn(null);
        assertThatThrownBy(() -> memberSvc().addMember("t1", "u1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("tenant-member");
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

    @Test void removing_the_last_admin_is_allowed() {
        var svc = memberSvc();
        when(utr.selectByTenantAndUser("t1", "u1")).thenReturn(List.of(utrRow("u1", "adminRole")));

        assertThatCode(() -> svc.removeMember("t1", "u1")).doesNotThrowAnyException();
        verify(utr).deleteByUserAndTenant("u1", "t1");
        verify(utm).deleteByUserAndTenant("u1", "t1");
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
        when(roleMapper.selectByCode("tenant-admin")).thenReturn(null);
        // 现在只有 listMembers 还需要它（判 owner）；grant/revoke 已不依赖 admin 角色
        assertThatThrownBy(() -> svc.listMembers("t1"))
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

    private static PlatformRole memberRole() {
        var r = new PlatformRole();
        r.setId("memberRole");
        r.setCode("tenant-member");
        r.setScope("TENANT");
        r.setBuiltIn((byte) 1);
        r.setStatus((byte) 1);
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
        return new TenantMemberService(utm, utr, roleMapper, userMapper, userRoleMapper);
    }

    // ---------- 平台侧身份不走租户授权（spec 2026-10-31 §3.2） ----------

    @Test void a_platform_user_cannot_be_added_as_a_new_member() {
        // 否则成员表就变成了"切换器开关"：管理员要靠被分配进租户才能切视图
        when(utm.exists("u1", "t1")).thenReturn(false);
        when(userRoleMapper.selectRoleCodesByUser("u1")).thenReturn(List.of("admin"));

        assertThatThrownBy(() -> memberSvc().addMember("t1", "u1"))
            .isInstanceOf(CloudPlatformException.class)
            .hasMessageContaining("平台角色");
        verify(utm, never()).insert(any());
    }

    @Test void an_existing_membership_of_a_platform_user_stays_idempotent() {
        // 历史行一律保留：撤销平台角色后要能回到原状态，删掉等于把降级做成不可逆
        when(utm.exists("u1", "t1")).thenReturn(true);

        assertThatCode(() -> memberSvc().addMember("t1", "u1")).doesNotThrowAnyException();
        verify(utm, never()).insert(any());
    }

    @Test void a_platform_user_cannot_be_granted_a_new_tenant_role() {
        when(utm.exists("u1", "t1")).thenReturn(true);
        when(roleMapper.selectByPrimaryKey("roleX")).thenReturn(tenantRole("roleX"));
        when(utr.selectByTenantAndUser("t1", "u1")).thenReturn(List.of());
        when(userRoleMapper.selectRoleCodesByUser("u1")).thenReturn(List.of("admin"));

        assertThatThrownBy(() -> memberSvc().grantRole("t1", "u1", "roleX"))
            .isInstanceOf(CloudPlatformException.class)
            .hasMessageContaining("平台角色");
        verify(utr, never()).insert(any());
    }
}
