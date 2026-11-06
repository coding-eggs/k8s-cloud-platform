package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.data.mapper.auth.PlatformRoleMapper;
import com.coding.data.mapper.auth.PlatformUserMapper;
import com.coding.data.mapper.auth.PlatformUserRoleMapper;
import com.coding.data.models.auth.PlatformRole;
import com.coding.data.models.auth.PlatformUser;
import com.coding.platformapi.models.UserCreateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceTest {
    PlatformUserMapper userMapper = mock(PlatformUserMapper.class);
    PlatformUserRoleMapper urMapper = mock(PlatformUserRoleMapper.class);
    PlatformRoleMapper roleMapper = mock(PlatformRoleMapper.class);
    com.coding.data.mapper.auth.PlatformTenantMapper tenantMapper =
            mock(com.coding.data.mapper.auth.PlatformTenantMapper.class);
    PasswordEncoder encoder = mock(PasswordEncoder.class);
    com.coding.data.mapper.auth.PlatformUserTenantMapper utMapper =
            mock(com.coding.data.mapper.auth.PlatformUserTenantMapper.class);
    com.coding.data.mapper.auth.PlatformUserTenantRoleMapper utrMapper =
            mock(com.coding.data.mapper.auth.PlatformUserTenantRoleMapper.class);
    com.coding.common.security.AuthContext auth = mock(com.coding.common.security.AuthContext.class);
    UserService svc = new UserService(userMapper, urMapper, roleMapper, tenantMapper, encoder, utMapper, utrMapper, auth);

    private PlatformUser user(String id, String username, int status) {
        var u = new PlatformUser();
        u.setId(id);
        u.setUsername(username);
        u.setStatus((byte) status);
        return u;
    }

    private void actingAs(String username) {
        var t = new com.coding.data.models.system.TokenUserInfo();
        t.setUsername(username);
        when(auth.current()).thenReturn(t);
    }

    private com.coding.platformapi.models.UserUpdateRequest upd(String id, String displayName, String email, Integer status) {
        var r = new com.coding.platformapi.models.UserUpdateRequest();
        r.setId(id);
        r.setDisplayName(displayName);
        r.setEmail(email);
        r.setStatus(status);
        return r;
    }

    // ---- /user/update ----

    @Test void update_writes_profile_and_blank_clears_field() {
        actingAs("admin");
        when(userMapper.selectByPrimaryKey("u1")).thenReturn(user("u1", "bob", 1));
        svc.update(upd("u1", "  Bob  ", "  ", 1));
        verify(userMapper).updateProfile("u1", "Bob", null, (byte) 1);
    }

    @Test void update_missing_user_rejected() {
        actingAs("admin");
        when(userMapper.selectByPrimaryKey("ghost")).thenReturn(null);
        assertThatThrownBy(() -> svc.update(upd("ghost", "x", null, 1))).isInstanceOf(CloudPlatformException.class);
        verify(userMapper, never()).updateProfile(any(), any(), any(), any());
    }

    @Test void cannot_disable_yourself() {
        actingAs("bob");
        when(userMapper.selectByPrimaryKey("u1")).thenReturn(user("u1", "bob", 1));
        assertThatThrownBy(() -> svc.update(upd("u1", "Bob", null, 0)))
            .isInstanceOf(CloudPlatformException.class).hasMessageContaining("自己");
        verify(userMapper, never()).updateProfile(any(), any(), any(), any());
    }

    @Test void cannot_disable_last_enabled_admin() {
        actingAs("root");
        when(userMapper.selectByPrimaryKey("u1")).thenReturn(user("u1", "bob", 1));
        when(urMapper.selectUserIdsByRoleCode("admin")).thenReturn(java.util.List.of("u1", "u2"));
        when(userMapper.selectByIds(java.util.List.of("u1", "u2")))
            .thenReturn(java.util.List.of(user("u1", "bob", 1), user("u2", "old", 0)));   // u2 已禁用
        assertThatThrownBy(() -> svc.update(upd("u1", "Bob", null, 0)))
            .isInstanceOf(CloudPlatformException.class).hasMessageContaining("管理员");
    }

    @Test void disabling_admin_ok_when_another_enabled_admin_remains() {
        actingAs("root");
        when(userMapper.selectByPrimaryKey("u1")).thenReturn(user("u1", "bob", 1));
        when(urMapper.selectUserIdsByRoleCode("admin")).thenReturn(java.util.List.of("u1", "u2"));
        when(userMapper.selectByIds(java.util.List.of("u1", "u2")))
            .thenReturn(java.util.List.of(user("u1", "bob", 1), user("u2", "root", 1)));
        svc.update(upd("u1", "Bob", null, 0));
        verify(userMapper).updateProfile("u1", "Bob", null, (byte) 0);
    }

    // ---- /user/delete ----

    @Test void delete_soft_deletes_and_drops_every_grant() {
        actingAs("root");
        when(userMapper.selectByPrimaryKey("u1")).thenReturn(user("u1", "bob", 1));
        when(urMapper.selectUserIdsByRoleCode("admin")).thenReturn(java.util.List.of());
        svc.delete("u1");
        verify(userMapper).softDelete("u1");
        verify(urMapper).deleteByUser("u1");
        verify(utMapper).deleteByUser("u1");
        verify(utrMapper).deleteByUser("u1");
        verify(userMapper, never()).deleteByPrimaryKey(any());   // 软删：审计、流水线等处的引用仍能对上人
    }

    @Test void cannot_delete_yourself() {
        actingAs("bob");
        when(userMapper.selectByPrimaryKey("u1")).thenReturn(user("u1", "bob", 1));
        assertThatThrownBy(() -> svc.delete("u1")).isInstanceOf(CloudPlatformException.class).hasMessageContaining("自己");
        verify(userMapper, never()).softDelete(any());
    }

    @Test void cannot_delete_last_enabled_admin() {
        actingAs("root");
        when(userMapper.selectByPrimaryKey("u1")).thenReturn(user("u1", "bob", 1));
        when(urMapper.selectUserIdsByRoleCode("admin")).thenReturn(java.util.List.of("u1"));
        when(userMapper.selectByIds(java.util.List.of("u1"))).thenReturn(java.util.List.of(user("u1", "bob", 1)));
        assertThatThrownBy(() -> svc.delete("u1")).isInstanceOf(CloudPlatformException.class).hasMessageContaining("管理员");
        verify(userMapper, never()).softDelete(any());
    }

    @Test void create_with_username_held_by_deleted_user_explains() {
        when(userMapper.selectByUsername("bob")).thenReturn(null);       // 未删除的里没有
        when(encoder.encode(any())).thenReturn("HASH");
        when(userMapper.insert(any())).thenThrow(new DuplicateKeyException("username"));   // 唯一索引仍被已删除用户占着
        assertThatThrownBy(() -> svc.create(req("bob")))
            .isInstanceOf(CloudPlatformException.class).hasMessageContaining("已删除");
    }

    @Test void duplicate_username_rejected() {
        when(userMapper.selectByUsername("bob")).thenReturn(new PlatformUser());
        assertThatThrownBy(() -> svc.create(req("bob")))
            .isInstanceOf(CloudPlatformException.class);
    }

    @Test void password_is_encoded() {
        when(userMapper.selectByUsername(any())).thenReturn(null);
        when(encoder.encode("secret")).thenReturn("HASH");
        var u = svc.create(req("bob"));
        assertThat(u.getPassword()).isEqualTo("HASH");
        verify(userMapper).insert(any());
    }

    @Test void grant_is_idempotent_on_duplicate() {
        when(userMapper.selectByPrimaryKey("u1")).thenReturn(new PlatformUser());
        when(roleMapper.selectByPrimaryKey("r1")).thenReturn(platformRole("r1"));
        when(urMapper.insert(any())).thenThrow(new DuplicateKeyException("uk_user_role"));
        assertThatCode(() -> svc.grantPlatformRole("u1", "r1")).doesNotThrowAnyException();
    }

    // ---- carry from Task 16 review：grantPlatformRole 角色域校验（与租户侧对称） ----

    @Test void grant_platform_scoped_role_accepted() {
        when(userMapper.selectByPrimaryKey("u1")).thenReturn(new PlatformUser());
        when(roleMapper.selectByPrimaryKey("platAdmin")).thenReturn(platformRole("platAdmin"));
        assertThatCode(() -> svc.grantPlatformRole("u1", "platAdmin")).doesNotThrowAnyException();
        verify(urMapper).insert(any());
    }

    @Test void grant_tenant_scoped_role_rejected() {
        when(userMapper.selectByPrimaryKey("u1")).thenReturn(new PlatformUser());
        PlatformRole tenant = new PlatformRole();
        tenant.setId("tenantRole");
        tenant.setScope("TENANT");
        tenant.setStatus((byte) 1);
        when(roleMapper.selectByPrimaryKey("tenantRole")).thenReturn(tenant);
        assertThatThrownBy(() -> svc.grantPlatformRole("u1", "tenantRole"))
            .isInstanceOf(CloudPlatformException.class);
        verify(urMapper, never()).insert(any());
    }

    @Test void grant_nonexistent_role_rejected() {
        when(userMapper.selectByPrimaryKey("u1")).thenReturn(new PlatformUser());
        when(roleMapper.selectByPrimaryKey("ghost")).thenReturn(null);
        assertThatThrownBy(() -> svc.grantPlatformRole("u1", "ghost"))
            .isInstanceOf(CloudPlatformException.class);
        verify(urMapper, never()).insert(any());
    }

    // ---- 终审 M5：grantPlatformRole 用户存在性校验（防悬挂 user_role 行） ----

    @Test void grant_nonexistent_user_rejected() {
        when(userMapper.selectByPrimaryKey("ghostUser")).thenReturn(null);
        assertThatThrownBy(() -> svc.grantPlatformRole("ghostUser", "r1"))
            .isInstanceOf(CloudPlatformException.class);
        verify(urMapper, never()).insert(any());
    }

    // ---- 平台角色回显：/user/platformRole/list（UserView 对话框从盲操作改为回显） ----

    @Test void platform_roles_of_returns_linked_roles() {
        when(userMapper.selectByPrimaryKey("u1")).thenReturn(new PlatformUser());
        when(urMapper.selectRoleIdsByUser("u1")).thenReturn(java.util.List.of("r1", "r2"));
        when(roleMapper.selectByIds(java.util.List.of("r1", "r2"))).thenReturn(java.util.List.of(platformRole("r1"), platformRole("r2")));
        var roles = svc.platformRolesOf("u1");
        assertThat(roles).extracting(PlatformRole::getId).containsExactly("r1", "r2");
    }

    @Test void platform_roles_of_empty_links_skips_role_query() {
        when(userMapper.selectByPrimaryKey("u1")).thenReturn(new PlatformUser());
        when(urMapper.selectRoleIdsByUser("u1")).thenReturn(java.util.List.of());
        assertThat(svc.platformRolesOf("u1")).isEmpty();
        verify(roleMapper, never()).selectByIds(any());
    }

    @Test void platform_roles_of_nonexistent_user_rejected() {
        when(userMapper.selectByPrimaryKey("ghostUser")).thenReturn(null);
        assertThatThrownBy(() -> svc.platformRolesOf("ghostUser"))
            .isInstanceOf(CloudPlatformException.class);
        verify(urMapper, never()).selectRoleIdsByUser(any());
    }

    private static PlatformRole platformRole(String id) {
        var r = new PlatformRole();
        r.setId(id);
        r.setCode("admin");
        r.setScope("PLATFORM");
        r.setStatus((byte) 1);
        return r;
    }

    private UserCreateRequest req(String n) {
        var r = new UserCreateRequest();
        r.setUsername(n);
        r.setPassword("secret");
        return r;
    }
}
