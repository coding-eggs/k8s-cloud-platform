package com.coding.platformapi.security;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /workloads/{name}/observability} 的权限行护栏。
 *
 * <p>为什么要读 SQL 文件：权限行是手工重放的，单测跑不到；漏写的症状是
 * {@code PermissionCrossCheckRunner} 在启动期直接拒绝启动（fail-closed），
 * 而不是运行时静默 403。这条测试把「行存在 + 复用 update 动词 + 绑定哪两个角色」
 * 钉在改动发生时。
 *
 * <p>Maven 跑测试时的工作目录是模块目录，故用相对路径取同级模块的迁移文件。
 */
class WorkloadObservabilityPermissionMigrationTest {

    private static final Path SQL = Path.of(
            "../platform-data/src/main/resources/db/migration/V2026_10_19_3__workload_observability_permission.sql");

    @Test
    void theEndpointHasItsOwnRowUsingTheSharedUpdateVerb() throws Exception {
        String sql = Files.readString(SQL);

        // 行本体：路径 + 方法 + 复用的既有动词码（改 pod template → 与 pause/mesh-toggle 同性质）
        assertThat(sql).contains("'/workloads/{name}/observability','POST','tenant:workload:update'");
    }

    @Test
    void adminAndTenantAdminAreBoundButTenantMemberIsNot() throws Exception {
        String sql = Files.readString(SQL);

        assertThat(sql).contains("'builtin_role_admin'");
        assertThat(sql).contains("'builtin_role_tenant_admin'");
        // 写操作（触发滚动更新），只读角色有意不给
        assertThat(sql).doesNotContain("'builtin_role_tenant_member'");
    }
}
