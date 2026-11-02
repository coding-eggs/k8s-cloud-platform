package com.coding.platformapi.app;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 发布接口权限迁移的内容检查：每个新端点都有行，写接口不绑 tenant_member。 */
class ApplicationReleasePermissionMigrationTest {

    private static final String SQL = read();

    private static String read() {
        try {
            return Files.readString(Path.of("../platform-data/src/main/resources/db/migration/"
                    + "V2026_11_02_11__application_release_permissions.sql"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void coversEveryNewEndpoint() {
        assertThat(SQL).contains(List.of(
                "/application/overview", "/application/release/preview", "/application/release/list",
                "/application/release/get", "/application/execution/active", "/application/service/create",
                "/application/service/update", "/application/service/remove", "/application/release/create",
                "/application/execution/resume").toArray(String[]::new));
    }

    @Test
    void everyPermissionRowIsBoundToAdminAndTenantAdmin() {
        for (String id : List.of("overview", "release_preview", "release_list", "release_get", "execution_active",
                "service_create", "service_update", "service_remove", "release_create", "execution_resume")) {
            assertThat(SQL).contains("'builtin_role_admin',        'perm_app_" + id + "'");
            assertThat(SQL).contains("'builtin_role_tenant_admin', 'perm_app_" + id + "'");
        }
    }

    @Test
    void tenantMemberOnlyGetsReadRows() {
        for (String id : List.of("service_create", "service_update", "service_remove", "release_create",
                "execution_resume")) {
            assertThat(SQL).doesNotContain("'builtin_role_tenant_member','perm_app_" + id + "'");
        }
        for (String id : List.of("overview", "release_preview", "release_list", "release_get",
                "execution_active")) {
            assertThat(SQL).contains("'builtin_role_tenant_member','perm_app_" + id + "'");
        }
    }
}
