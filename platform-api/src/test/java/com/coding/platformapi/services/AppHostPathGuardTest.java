package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.security.AuthContext;
import com.coding.data.models.system.TokenUserInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppHostPathGuardTest {

    private static final Map<String, Object> HOST_PATH = Map.of("volumes", List.of(
            Map.of("name", "h", "type", "hostPath", "source", "/var/log", "mountPath", "/hostlog")));

    private static AppHostPathGuard guard(List<String> platformRoles) {
        AuthContext auth = mock(AuthContext.class);
        if (platformRoles != null) {
            TokenUserInfo me = new TokenUserInfo();
            me.setPlatformRoles(platformRoles);
            when(auth.current()).thenReturn(me);
        }
        return new AppHostPathGuard(auth);
    }

    @Test
    void tenantSavingHostPathIsRejected() {
        assertThatThrownBy(() -> guard(List.of("tenant-owner")).assertAllowed(HOST_PATH))
                .isInstanceOf(CloudPlatformException.class).hasMessageContaining("只允许平台管理员");
        // 没有身份（未认证 / claim 缺失）同样按非管理员处理
        assertThatThrownBy(() -> guard(null).assertAllowed(HOST_PATH))
                .isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void adminMayUseHostPath() {
        assertThatCode(() -> guard(List.of("admin")).assertAllowed(HOST_PATH)).doesNotThrowAnyException();
    }

    @Test
    void nonHostPathVolumesNeedNoAdmin() {
        Map<String, Object> pvc = Map.of("volumes", List.of(
                Map.of("name", "d", "type", "pvc", "source", "my-pvc", "mountPath", "/mnt")));
        assertThatCode(() -> guard(null).assertAllowed(pvc)).doesNotThrowAnyException();
        assertThatCode(() -> guard(List.of("tenant-owner")).assertAllowed(Map.of())).doesNotThrowAnyException();
    }
}
