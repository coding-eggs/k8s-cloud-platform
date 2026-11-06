package com.coding.platformapi.app;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 下线接口权限迁移的内容检查：有行、写码、只绑 admin 与 tenant_admin。 */
class ApplicationOfflinePermissionMigrationTest {

    private static final String SQL = read();

    private static String read() {
        try {
            return Files.readString(Path.of("../platform-data/src/main/resources/db/migration/"
                    + "V2026_11_06_1__application_release_offline_permission.sql"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void offlineEndpointHasAWriteRow() {
        assertThat(SQL).contains("'/application/release/offline'").contains("'tenant:application:write'");
    }

    @Test
    void boundToAdminAndTenantAdminButNotTenantMember() {
        assertThat(SQL).contains("'builtin_role_admin',        'perm_app_release_offline'");
        assertThat(SQL).contains("'builtin_role_tenant_admin', 'perm_app_release_offline'");
        assertThat(SQL).doesNotContain("'builtin_role_tenant_member'");
    }
}
