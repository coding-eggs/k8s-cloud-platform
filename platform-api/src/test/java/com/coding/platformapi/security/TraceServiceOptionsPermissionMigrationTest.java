package com.coding.platformapi.security;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code GET /traces/services} 的权限行护栏（漏写会让 platform-api 启动失败）。 */
class TraceServiceOptionsPermissionMigrationTest {

    private static final Path SQL = Path.of(
            "../platform-data/src/main/resources/db/migration/V2026_10_19_5__trace_service_options.sql");

    @Test
    void theServiceOptionsEndpointReusesTheTraceSearchCodeAndIsAdminOnly() throws Exception {
        String sql = Files.readString(SQL);

        assertThat(sql).contains("'/traces/services'");
        assertThat(sql).contains("'platform:trace:search'");
        assertThat(sql).contains("'builtin_role_admin'");
    }
}
