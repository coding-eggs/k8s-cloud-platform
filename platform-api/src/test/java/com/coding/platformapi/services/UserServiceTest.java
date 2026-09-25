package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.data.mapper.auth.PlatformUserMapper;
import com.coding.data.mapper.auth.PlatformUserRoleMapper;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceTest {
    PlatformUserMapper userMapper = mock(PlatformUserMapper.class);
    PlatformUserRoleMapper urMapper = mock(PlatformUserRoleMapper.class);
    PasswordEncoder encoder = mock(PasswordEncoder.class);
    UserService svc = new UserService(userMapper, urMapper, encoder);

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
        when(urMapper.insert(any())).thenThrow(new DuplicateKeyException("uk_user_role"));
        assertThatCode(() -> svc.grantPlatformRole("u1", "r1")).doesNotThrowAnyException();
    }

    private UserCreateRequest req(String n) {
        var r = new UserCreateRequest();
        r.setUsername(n);
        r.setPassword("secret");
        return r;
    }
}
