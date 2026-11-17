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

    // ---- V2026_11_18_2：历史运行的变更清单端点 ----

    private static final String DIFF_SQL = readFile("V2026_11_18_2__pipeline_run_diff.sql");

    private static String readFile(String name) {
        try {
            return Files.readString(Path.of("../platform-data/src/main/resources/db/migration/" + name));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void theHistoricalDiffEndpointHasAPermissionRowToo() {
        // 新端点没有权限行 = PermissionCrossCheckRunner 拒启（fail-closed）
        assertThat(DIFF_SQL).contains("'/pipeline/run/diff'");
        // 能运行的人才能看它跑了哪些文件 —— 与预览同一个码
        assertThat(DIFF_SQL).contains("'tenant:pipeline:run'");
        assertThat(DIFF_SQL).contains("'perm_pipeline_rundiff'");
    }

    @Test
    void theDiffRowIsBoundToWhoeverCanTriggerRuns() {
        assertThat(DIFF_SQL).contains("WHERE rp.permission_id = 'perm_pipeline_run'");
    }

    // ---- V2026_11_18_3：单文件补丁端点 ----

    private static final String PATCH_SQL = readFile("V2026_11_18_3__pipeline_run_diff_file.sql");

    @Test
    void theFilePatchEndpointHasAPermissionRowToo() {
        assertThat(PATCH_SQL).contains("'/pipeline/run/diff/file'");
        assertThat(PATCH_SQL).contains("'tenant:pipeline:run'");
        assertThat(PATCH_SQL).contains("'perm_pipeline_runpatch'");
        // 看不到绑定，新端点对非内置角色就是 403
        assertThat(PATCH_SQL).contains("WHERE rp.permission_id = 'perm_pipeline_run'");
    }
}
