package com.coding.platformapi.security;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 新端点没有权限行 = 启动期 fail-closed（PermissionCrossCheckRunner）。 */
class RegistryWarehousePermissionMigrationTest {

    private static final Path SQL = Path.of(
            "../platform-data/src/main/resources/db/migration/V2026_10_29_2__registry_warehouses.sql");

    @Test
    void warehouseEndpointHasAPermissionRowBoundToAllBuiltinRoles() throws Exception {
        String sql = Files.readString(SQL);

        assertThat(sql).contains("'/registry/warehouses'")
                .contains("'POST'")
                .contains("'tenant:registry:catalog'");
        assertThat(sql).contains("'builtin_role_admin'")
                .contains("'builtin_role_tenant_admin'")
                .contains("'builtin_role_tenant_member'");
    }
}
