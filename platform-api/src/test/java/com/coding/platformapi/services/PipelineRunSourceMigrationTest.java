package com.coding.platformapi.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 源码预览迁移的内容检查（{@code V2026_11_18_1__pipeline_run_source.sql}）。
 *
 * <p>两个东西漏一个都会出事：
 * <ul>
 *   <li>{@code source_json} 列 —— 新 mapper 会读写它，没有列就是运行期 SQL 报错；</li>
 *   <li>预览端点的权限行 —— 没有行，{@code PermissionCrossCheckRunner} 直接拒启。</li>
 * </ul>
 * 权限**绑定**也要复制：新行不会按 code 自动继承到角色上。
 */
class PipelineRunSourceMigrationTest {

    private static final String SQL = read();

    private static String read() {
        try {
            return Files.readString(Path.of("../platform-data/src/main/resources/db/migration/"
                    + "V2026_11_18_1__pipeline_run_source.sql"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void theSourceColumnIsAddedIdempotently() {
        assertThat(SQL).contains("'platform_pipeline_run'");
        assertThat(SQL).contains("'source_json'");
        // 手工重放的迁移必须幂等：先查 information_schema 再 ALTER
        assertThat(SQL).contains("information_schema.COLUMNS");
    }

    @Test
    void thePreviewEndpointHasAPermissionRowUnderTheRunCode() {
        assertThat(SQL).contains("'/pipeline/run/preview'");
        // 能运行的人才能预览源码 —— 不新增一套码
        assertThat(SQL).contains("'tenant:pipeline:run'");
    }

    @Test
    void theNewRowIsBoundToWhoeverCanTriggerRuns() {
        // 绑定从 /pipeline/run 那行复制（内置角色与自定义角色一视同仁）
        assertThat(SQL).contains("WHERE rp.permission_id = 'perm_pipeline_run'");
        assertThat(SQL).contains("'perm_pipeline_runpreview'");
    }
}
