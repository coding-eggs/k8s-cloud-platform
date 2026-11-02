package com.coding.platformapi.app;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 发布模型迁移的内容检查（没有 Flyway，单测里也不连库）。 */
class ApplicationReleaseMigrationTest {

    private static final String SQL = read();

    private static String read() {
        try {
            return Files.readString(Path.of(
                    "../platform-data/src/main/resources/db/migration/V2026_11_02_10__application_release.sql"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void createsFourTablesIdempotently() {
        for (String t : new String[]{"platform_app_service", "platform_app_dependency",
                "platform_app_release", "platform_app_execution"}) {
            assertThat(SQL).contains("CREATE TABLE IF NOT EXISTS " + t + " ");
        }
    }

    @Test
    void declaresUniqueKeys() {
        assertThat(SQL).contains("UNIQUE KEY uk_app_service (app_id, service_key)");
        assertThat(SQL).contains("UNIQUE KEY uk_app_dep (app_id, dep_key)");
        assertThat(SQL).contains("UNIQUE KEY uk_app_release_no (app_id, release_no)");
    }

    @Test
    void executionHasActiveUniqueKeyExcludingFailed() {
        assertThat(SQL).contains("IF(status IN ('PENDING','RUNNING','INTERRUPTED'), app_id, NULL)");
        assertThat(SQL).contains("UNIQUE KEY uk_app_exec_active (active_key)");
    }

    @Test
    void alterOfApplicationIsGuarded() {
        assertThat(SQL).contains("live_release_no");
        assertThat(SQL).contains("information_schema.COLUMNS");
        assertThat(SQL).contains("MODIFY COLUMN spec_json MEDIUMTEXT NULL");
    }

    @Test
    void hasNoForeignKeys() {
        assertThat(SQL.toUpperCase()).doesNotContain("FOREIGN KEY").doesNotContain("REFERENCES ");
    }
}
