package com.coding.platformapi.security;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 凭据对租户开放（2026-10-29）的迁移护栏。 */
class CredentialTenantMigrationTest {

    private static final Path SQL = Path.of(
            "../platform-data/src/main/resources/db/migration/V2026_10_29_3__credential_tenant_open.sql");

    @Test
    void fiveCredentialEndpointsGetTenantCodes() throws Exception {
        String sql = Files.readString(SQL);

        for (String path : new String[]{"/credential/list", "/credential/test",
                "/credential/create", "/credential/update", "/credential/delete"}) {
            assertThat(sql).as("端点 %s 必须有租户码的权限行", path).contains("'" + path + "'");
        }
        assertThat(sql).contains("'tenant:credential:read'").contains("'tenant:credential:write'");
    }

    @Test
    void writeCodeBindsOnlyAdminAndTenantAdmin() throws Exception {
        String sql = Files.readString(SQL);

        // read 三条角色都有；write 只有 admin 与 tenant_admin（tenant_member 只读）
        assertThat(sql).contains("'builtin_role_tenant_member','perm_tenant_credential_list'");
        assertThat(sql).doesNotContain("'builtin_role_tenant_member','perm_tenant_credential_create'");
    }

    @Test
    void gitCredentialsPageMovesToItsOwnRoute() throws Exception {
        String sql = Files.readString(SQL);

        assertThat(sql).contains("/git-credentials").contains("'tenant:page:credential'");
    }
}
