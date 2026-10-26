package com.coding.k8sserver.middleware;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milvus 用户管理的纯函数部分：角色过滤与请求体字段（RESTful v2 用 camelCase）。
 */
class MilvusUserAdapterTest {

    @Test
    void assignableRolesDropsPublicBecauseItHasNoPrivileges() {
        assertThat(MilvusUserAdapter.assignableRoles(List.of("admin", "public"))).containsExactly("admin");
        assertThat(MilvusUserAdapter.assignableRoles(List.of("admin"))).containsExactly("admin");
        assertThat(MilvusUserAdapter.assignableRoles(List.of())).isEmpty();
    }

    @Test
    void createBodyUsesMilvusFieldNames() {
        assertThat(MilvusUserAdapter.createBody("alice", "pw-123456"))
                .containsEntry("userName", "alice")
                .containsEntry("password", "pw-123456");
    }

    @Test
    void grantBodyBindsRoleToUser() {
        assertThat(MilvusUserAdapter.grantBody("admin", "alice"))
                .containsEntry("roleName", "admin")
                .containsEntry("userName", "alice");
    }

    @Test
    void baseUrlFollowsTheServiceNamingConvention() {
        assertThat(MilvusUserAdapter.baseUrl("mw-milvus-abc12345", "movie"))
                .isEqualTo("http://mw-milvus-abc12345-milvus.movie.svc:19530");
    }

    @Test
    void rootIsTheOnlyBuiltin() {
        assertThat(MilvusUserAdapter.BUILTIN).isEqualTo("root");
    }

    @Test
    void parsesUsersFromTheRestPayload() {
        String json = "{\"code\":0,\"data\":[\"root\",\"alice\"]}";

        assertThat(MilvusUserAdapter.parseNames(json)).containsExactly("root", "alice");
    }

    @Test
    void rolessListIsReadFromTheDataArray() {
        String json = "{\"code\":0,\"data\":[\"admin\",\"public\"]}";

        assertThat(MilvusUserAdapter.parseNames(json)).containsExactly("admin", "public");
    }

    private static Map<String, Object> map(String userName, String password) {
        return Map.of("userName", userName, "password", password);
    }
}
