package com.coding.platformapi.components;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批①的两个迁移约束：cluster_role 列 + 三条新端点的权限行。
 * 读 SQL 文件而不是连库：这条护栏要在任何环境下都能跑。
 */
class ComponentLifecycleMigrationTest {

    private static final Path SQL = Path.of(
            "../platform-data/src/main/resources/db/migration/V2026_10_18_1__component_lifecycle.sql");

    @Test
    void clusterRoleColumnIsAddedWithBusinessDefault() throws Exception {
        String sql = Files.readString(SQL);
        // ⚠️ MySQL 不支持 ADD COLUMN IF NOT EXISTS，迁移用的是 information_schema + PREPARE 的
        // 幂等写法（仓库既有模式，见 V2026_10_15_1），所以这里只断言列名与默认值都出现。
        assertThat(sql).containsIgnoringCase("add column cluster_role");
        assertThat(sql).contains("BUSINESS");
        assertThat(sql).containsIgnoringCase("information_schema.columns");
    }

    @Test
    void theOperationTableExists() throws Exception {
        String sql = Files.readString(SQL);
        assertThat(sql).containsIgnoringCase("create table if not exists platform_component_operation");
        for (String col : new String[]{"cluster_id", "component_code", "action", "profile",
                "from_version", "to_version", "release_names", "values_snapshot",
                "status", "message", "log_tail", "operator", "started_at", "finished_at"}) {
            assertThat(sql).as("缺列 %s", col).contains(col);
        }
    }

    @Test
    void concurrencyIsGuardedByGeneratedColumns() throws Exception {
        // ⭐ 评审发现：countRunning + insert 是 TOCTOU。用生成列 + 唯一键兜住。
        String sql = Files.readString(SQL);
        assertThat(sql).contains("running_lock");
        assertThat(sql).contains("uk_component_op_running");
        assertThat(sql).contains("management_lock");
        assertThat(sql).contains("uk_cluster_management");
    }

    @Test
    void staleSweepAlsoCoversPendingOperations() throws Exception {
        // 评审发现：只看 RUNNING 会让"重启时还没超时"的 PENDING 永远卡住
        String xml = Files.readString(Path.of(
                "../platform-data/src/main/resources/mapper/system/PlatformComponentOperationMapper.xml"));
        assertThat(xml).contains("'PENDING', 'RUNNING'");
    }

    @Test
    void allThreeEndpointsHavePermissionRowsBoundToAdmin() throws Exception {
        String sql = Files.readString(SQL);
        assertThat(sql).contains("'/cluster/components/apply'");
        assertThat(sql).contains("'/cluster/components/operations/get'");
        assertThat(sql).contains("'/cluster/components/operations/list'");
        assertThat(sql).contains("builtin_role_admin");
        // 三条都是 platform:cluster:manage
        assertThat(count(sql, "'platform:cluster:manage'")).isGreaterThanOrEqualTo(3);
    }

    @Test
    void theWaypointTracingEndpointsHavePermissionRowsAndBindings() throws Exception {
        String sql = Files.readString(Path.of(
                "../platform-data/src/main/resources/db/migration/V2026_10_19_1__mesh_waypoint_tracing.sql"));
        assertThat(sql).contains("'/mesh/waypoints/tracing'")
                .contains("tenant:gateway:update")
                .contains("tenant:gateway:get")
                .contains("builtin_role_admin")
                .contains("builtin_role_tenant_admin")
                .contains("builtin_role_tenant_member");
    }

    @Test
    void theTraceEndpointsAndPageHavePermissionRowsBoundToAdmin() throws Exception {
        String sql = Files.readString(Path.of(
                "../platform-data/src/main/resources/db/migration/V2026_10_19_2__trace_view_permissions.sql"));
        assertThat(sql).contains("'/traces/search'")
                .contains("'/traces/{traceId}'")
                .contains("'/mesh/traces'")
                .contains("platform:trace:search")
                .contains("platform:trace:get")
                .contains("platform:page:mesh.trace")
                .contains("builtin_role_admin");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
