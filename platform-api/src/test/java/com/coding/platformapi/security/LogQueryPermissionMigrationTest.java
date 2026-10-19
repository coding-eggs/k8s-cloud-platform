package com.coding.platformapi.security;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 日志查询中心的权限行护栏（漏写会让 platform-api 启动失败 / 租户进不了页面）。 */
class LogQueryPermissionMigrationTest {

    private static final Path SQL = Path.of(
            "../platform-data/src/main/resources/db/migration/V2026_10_19_6__log_query_center.sql");

    @Test
    void theSearchEndpointAndPageAreBoundToAllThreeBuiltinRoles() throws Exception {
        String sql = Files.readString(SQL);

        assertThat(sql).contains("'/logs/search'").contains("tenant:log:query");
        assertThat(sql).contains("'/observability/logs'").contains("tenant:page:observability.logs");
        assertThat(sql).contains("builtin_role_admin")
                .contains("builtin_role_tenant_admin")
                .contains("builtin_role_tenant_member");
    }
}
