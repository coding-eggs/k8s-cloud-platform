package com.coding.platformapi.security;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 租户链路权限行护栏：漏写会让租户 trace 端点 403（或启动交叉校验失败）。 */
class TenantTracePermissionMigrationTest {

    private static final Path SQL = Path.of(
            "../platform-data/src/main/resources/db/migration/V2026_10_19_7__tenant_trace_permissions.sql");

    @Test
    void itGrantsTenantRolesTheTenantTraceCodes() throws Exception {
        String sql = Files.readString(SQL);

        assertThat(sql).contains("'tenant:trace:search'")
                .contains("'tenant:trace:get'")
                .contains("'tenant:page:mesh.trace'")
                .contains("'/traces/search'")
                .contains("'/traces/{traceId}'")
                .contains("'/traces/services'")
                .contains("'builtin_role_tenant_admin'")
                .contains("'builtin_role_tenant_member'");
    }
}
