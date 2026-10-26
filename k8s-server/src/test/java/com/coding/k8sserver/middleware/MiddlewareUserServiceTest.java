package com.coding.k8sserver.middleware;

import com.coding.common.exception.CloudPlatformException;
import com.coding.k8score.factory.KubernetesClientFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 分派规则：哪些 code 能进来、哪些只能改口令。
 */
class MiddlewareUserServiceTest {

    private final KubernetesClientFactory clients = mock(KubernetesClientFactory.class);

    @Test
    void unknownCodeIsRejected() {
        var svc = new MiddlewareUserService(List.of(), clients);

        assertThatThrownBy(() -> svc.adapterFor("oracle"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("暂不支持用户管理");
    }

    @Test
    void redisOnlyAllowsPasswordChange() {
        var redis = mock(MiddlewareUserAdapter.class);
        when(redis.code()).thenReturn("redis");
        var svc = new MiddlewareUserService(List.of(redis), clients);

        assertThatThrownBy(() -> svc.requireCreatable("redis"))
                .hasMessageContaining("不支持新增用户");
        assertThatThrownBy(() -> svc.requireDeletable("redis"))
                .hasMessageContaining("不支持删除用户");
        assertThatCode(() -> svc.requirePasswordChangeable("redis")).doesNotThrowAnyException();
    }
}
