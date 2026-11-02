package com.coding.platformapi.app;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 画布遗留清理迁移的内容检查（没有 Flyway，也没法在单测里连库）。
 * 钉住：删哪些、留哪些，以及「有已部署旧应用就不动结构」的前提。
 */
class ApplicationCanvasCleanupMigrationTest {

    private static final String SQL = read();

    private static String read() {
        try {
            return Files.readString(Path.of("../platform-data/src/main/resources/db/migration/"
                    + "V2026_11_02_14__drop_application_canvas_legacy.sql"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void dropsTheTwoCanvasTablesAndColumns() {
        assertThat(SQL).contains("DROP TABLE IF EXISTS platform_application_revision");
        assertThat(SQL).contains("DROP TABLE IF EXISTS platform_application_operation");
        assertThat(SQL).contains("DROP COLUMN spec_json");
        assertThat(SQL).contains("DROP COLUMN current_revision");
    }

    @Test
    void keepsWhatTheNewModelStillUses() {
        assertThat(SQL).doesNotContain("DROP TABLE IF EXISTS platform_application_resource");
        assertThat(SQL).doesNotContain("DROP TABLE IF EXISTS platform_application_reference");
        assertThat(SQL).doesNotContain("DROP COLUMN app_id");
        assertThat(SQL).doesNotContain("DROP COLUMN app_node_id");
    }

    @Test
    void deletesOnlyTheFiveDeadEndpointPermissionsWithTheirBindings() {
        List<String> dead = List.of("perm_app_preview", "perm_app_operation_get", "perm_app_revision_list",
                "perm_app_update", "perm_app_deploy");
        assertThat(SQL).contains(dead.toArray(String[]::new));
        assertThat(SQL.indexOf("DELETE FROM platform_role_permission"))
                .isLessThan(SQL.indexOf("DELETE FROM platform_permission"));
        // 还活着的端点不许被顺手删掉
        assertThat(SQL).doesNotContain("'perm_app_list'", "'perm_app_get'", "'perm_app_create'",
                "'perm_app_delete'", "'perm_app_options'", "'perm_page_application'");
    }

    @Test
    void structuralStepsAreGatedByTheDeployedLegacyPrecheck() {
        assertThat(SQL).contains("SELECT COUNT(*) INTO @blocked");
        assertThat(SQL).contains("IF(@blocked = 0, 'DROP TABLE IF EXISTS platform_application_revision'");
        assertThat(SQL).contains("IF(@blocked = 0, 'DROP TABLE IF EXISTS platform_application_operation'");
        assertThat(SQL).contains("SET @legacy := IF(@has_spec = 1 AND @blocked = 0, 1, 0)");
        assertThat(SQL).contains("IF(@legacy = 1, 'ALTER TABLE platform_application DROP COLUMN spec_json'");
    }
}
