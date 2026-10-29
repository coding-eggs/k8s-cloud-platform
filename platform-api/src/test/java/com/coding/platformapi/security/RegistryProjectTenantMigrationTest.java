package com.coding.platformapi.security;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租户镜像项目列表对租户开放（2026-10-29 评审修复）的迁移护栏。
 *
 * <p>端点没变（还是 {@code /registry/projects/list}），只是多了一条 {@code tenant:} 码 ——
 * 租户角色不能持 {@code platform:} 码，所以那一条必须新开。
 */
class RegistryProjectTenantMigrationTest {

    private static final Path SQL = Path.of(
            "../platform-data/src/main/resources/db/migration/"
                    + "V2026_10_29_4__registry_project_tenant_read.sql");

    @Test
    void projectListGetsATenantReadCodeBoundToAllBuiltinRoles() throws Exception {
        String sql = Files.readString(SQL);

        assertThat(sql).contains("'/registry/projects/list'")
                .contains("'tenant:registry:project:read'");
        assertThat(sql).contains("'builtin_role_admin'")
                .contains("'builtin_role_tenant_admin'")
                .contains("'builtin_role_tenant_member'");
    }

    @Test
    void manageStaysAdminOnly() throws Exception {
        String sql = Files.readString(SQL);

        // 同步 / 重建**不**在本文件里加租户绑定
        assertThat(sql).doesNotContain("perm_registry_projects_sync")
                .doesNotContain("perm_registry_projects_rebuild");
    }
}
