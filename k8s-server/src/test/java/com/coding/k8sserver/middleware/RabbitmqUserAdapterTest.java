package com.coding.k8sserver.middleware;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RabbitMQ 的纯函数部分：角色 → 权限三标志 / 用户体 / 内部账号过滤。
 */
class RabbitmqUserAdapterTest {

    @Test
    void permissionsMapRolesToTheThreeFlags() {
        assertThat(RabbitmqUserAdapter.permissions("只读"))
                .containsEntry("configure", "")
                .containsEntry("write", "")
                .containsEntry("read", ".*");
        assertThat(RabbitmqUserAdapter.permissions("读写"))
                .containsEntry("configure", ".*")
                .containsEntry("write", ".*")
                .containsEntry("read", ".*");
    }

    @Test
    void adminRoleAddsTheAdministratorTag() {
        assertThat(RabbitmqUserAdapter.userBody("pw-123456", "管理员"))
                .containsEntry("password", "pw-123456")
                .containsEntry("tags", "administrator");
        assertThat(RabbitmqUserAdapter.userBody("pw-123456", "只读")).containsEntry("tags", "");
    }

    @Test
    void internalUsersAreFilteredOut() {
        assertThat(RabbitmqUserAdapter.isInternal("rabbitmq_management")).isTrue();
        assertThat(RabbitmqUserAdapter.isInternal("guest")).isTrue();
        assertThat(RabbitmqUserAdapter.isInternal("alice")).isFalse();
    }

    @Test
    void parsesUserNamesFromTheManagementApiPayload() throws Exception {
        String json = "[{\"name\":\"user\",\"tags\":[\"administrator\"]},"
                + "{\"name\":\"alice\",\"tags\":[]},"
                + "{\"name\":\"rabbitmq_management\",\"tags\":[\"administrator\"]}]";

        assertThat(RabbitmqUserAdapter.parseUsers(json))
                .containsExactly("user", "alice");
    }
}
