package com.coding.platformapi.app;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 过程日志迁移的内容检查：建表幂等、读接口走 read 码、三个内置角色都绑上。 */
class ApplicationExecutionLogMigrationTest {

    private static final String SQL = read();

    private static String read() {
        try {
            return Files.readString(Path.of("../platform-data/src/main/resources/db/migration/"
                    + "V2026_11_06_2__application_execution_log.sql"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void createsTableIdempotently() {
        assertThat(SQL).contains("CREATE TABLE IF NOT EXISTS platform_app_execution_log");
    }

    @Test
    void logsEndpointIsAReadRowBoundToAllBuiltinRoles() {
        assertThat(SQL).contains("'/application/execution/logs'").contains("'tenant:application:read'");
        assertThat(SQL).contains("'builtin_role_admin',         'perm_app_execution_logs'");
        assertThat(SQL).contains("'builtin_role_tenant_admin',  'perm_app_execution_logs'");
        assertThat(SQL).contains("'builtin_role_tenant_member', 'perm_app_execution_logs'");
    }
}
