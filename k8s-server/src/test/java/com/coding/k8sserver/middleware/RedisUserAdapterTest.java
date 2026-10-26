package com.coding.k8sserver.middleware;

import com.coding.common.exception.CloudPlatformException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Redis 只做"改口令"：opstree 不管理 ACL 用户，增删用户一律拒绝。
 */
class RedisUserAdapterTest {

    private final RedisUserAdapter adapter = new RedisUserAdapter();

    @Test
    void onlyTheDefaultUserIsListed() {
        var users = RedisUserAdapter.builtinUsers();

        assertThat(users).singleElement().satisfies(u -> {
            assertThat(u.getUsername()).isEqualTo("default");
            assertThat(u.isBuiltin()).isTrue();
        });
    }

    @Test
    void creatingOrDroppingUsersIsRejected() {
        var ref = new MiddlewareUserRef("mw-redis-abcdefgh", "demo", "mwi_1");

        assertThatThrownBy(() -> adapter.create(null, ref, "alice", "pw-123456", "只读"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("只支持修改口令");
        assertThatThrownBy(() -> adapter.drop(null, ref, "alice"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("只支持修改口令");
    }

    @Test
    void credentialsSecretNameFollowsTheAdapterConvention() {
        assertThat(RedisUserAdapter.credentialsSecretName("mw-redis-abcdefgh"))
                .isEqualTo("mw-redis-abcdefgh-credentials");
    }
}
