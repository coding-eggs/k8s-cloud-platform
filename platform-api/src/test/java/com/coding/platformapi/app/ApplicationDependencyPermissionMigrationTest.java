package com.coding.platformapi.app;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 依赖接口权限迁移的内容检查：三个写端点、写码、只绑 admin 与 tenant_admin。 */
class ApplicationDependencyPermissionMigrationTest {

    private static final String SQL = read();

    private static String read() {
        try {
            return Files.readString(Path.of("../platform-data/src/main/resources/db/migration/"
                    + "V2026_11_02_1__application_dependency_permissions.sql"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void threeWriteEndpointsHaveRows() {
        for (String p : new String[]{"add", "remove", "init"}) {
            assertThat(SQL).contains("'/application/dependency/" + p + "'");
        }
        assertThat(SQL).doesNotContain("tenant:application:read");
        assertThat(SQL).contains("'tenant:application:write'");
    }

    @Test
    void boundToAdminAndTenantAdminButNotTenantMember() {
        for (String p : new String[]{"add", "remove", "init"}) {
            assertThat(SQL).contains("'builtin_role_admin',        'perm_app_dependency_" + p + "'");
            assertThat(SQL).contains("'builtin_role_tenant_admin', 'perm_app_dependency_" + p + "'");
        }
        assertThat(SQL).doesNotContain("'builtin_role_tenant_member'");
    }
}
