package com.coding.platformapi.app;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 计划②的迁移：来源列 + 引用表（计划①的设计里列了这张表，但迁移漏建）。 */
class ApplicationMiddlewareMigrationTest {

    private static final String SQL = read();

    private static String read() {
        try {
            return Files.readString(Path.of(
                    "../platform-data/src/main/resources/db/migration/V2026_10_26_1__application_middleware.sql"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void addsApplicationColumnsIdempotently() {
        assertThat(SQL).contains("app_id").contains("app_node_id");
        assertThat(SQL).contains("information_schema.COLUMNS");
        assertThat(SQL).contains("platform_middleware_instance");
    }

    @Test
    void createsReferenceTable() {
        assertThat(SQL).contains("CREATE TABLE IF NOT EXISTS platform_application_reference");
        assertThat(SQL).contains("ref_type").contains("ref_id");
        assertThat(SQL).contains("uk_app_ref");
    }
}
