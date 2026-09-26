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
    PasswordEncoder encoder = mock(PasswordEncoder.class);
    UserService svc = new UserService(userMapper, urMapper, roleMapper, encoder);

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
