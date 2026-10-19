package com.coding.platformapi.security;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /cluster/components/operations/{id}/abandon} 的权限行护栏。
 *
 * <p>权限行是手工重放的，单测跑不到；漏写的症状是 {@code PermissionCrossCheckRunner}
 * 在启动期直接拒绝启动（fail-closed）。
 */
class ComponentAbandonPermissionMigrationTest {

    private static final Path SQL = Path.of(
            "../platform-data/src/main/resources/db/migration/V2026_10_19_4__component_operation_abandon.sql");

    @Test
    void theAbandonEndpointHasARowUsingClusterManageBoundToAdmin() throws Exception {
        String sql = Files.readString(SQL);

        assertThat(sql).contains("'/cluster/components/operations/{id}/abandon'");
        assertThat(sql).contains("'platform:cluster:manage'");
        assertThat(sql).contains("'builtin_role_admin'");
    }
}
