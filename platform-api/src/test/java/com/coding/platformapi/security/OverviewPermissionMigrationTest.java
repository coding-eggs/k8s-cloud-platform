package com.coding.platformapi.security;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 总览 / 概览计数卡端点的权限行护栏（表驱动授权：漏一行 platform-api 启动即 fail-closed；
 * 漏绑角色则该角色 403）。
 */
class OverviewPermissionMigrationTest {

    private static final Path SQL = Path.of(
            "../platform-data/src/main/resources/db/migration/V2026_11_06_4__overview_permissions.sql");

    private static final String[][] ROWS = {
            // id, resource, action, code, roles...
            {"perm_overview_platform", "/overview/platform", "POST", "platform:overview:view", "builtin_role_admin"},
            {"perm_cluster_summary", "/cluster/summary", "POST", "platform:cluster:manage", "builtin_role_admin"},
            {"perm_ns_summary", "/namespace/summary", "POST", "platform:allocation:list", "builtin_role_admin"},
            {"perm_tenant_summary_self", "/tenant/overview/summary", "POST", "tenant:overview:view",
                    "builtin_role_admin", "builtin_role_tenant_admin", "builtin_role_tenant_member"},
            {"perm_tenant_summary_proxy", "/tenant/overview/summary", "POST", "platform:allocation:list", "builtin_role_admin"},
            {"perm_node_podstat", "/nodes/{name}/podstat", "GET", "platform:cluster:manage", "builtin_role_admin"},
    };

    @Test
    void everyRowPresentAndBound() throws Exception {
        String sql = Files.readString(SQL);
        for (String[] r : ROWS) {
            assertThat(sql).as("权限行 " + r[0])
                    .containsPattern(Pattern.quote("'" + r[0] + "'") + "\\s*,\\s*'API'\\s*,\\s*"
                            + Pattern.quote("'" + r[1] + "'") + "\\s*,\\s*'" + r[2] + "'\\s*,\\s*"
                            + Pattern.quote("'" + r[3] + "'"));
            for (int i = 4; i < r.length; i++) {
                assertThat(sql).as(r[0] + " 绑定 " + r[i])
                        .containsPattern("'" + r[i] + "'\\s*,\\s*'" + r[0] + "'");
            }
        }
    }

    @Test
    void noRoleBindingToUnknownPermission() throws Exception {
        String sql = Files.readString(SQL);
        Matcher m = Pattern.compile("'builtin_role_\\w+'\\s*,\\s*'(perm_\\w+)'").matcher(sql);
        int n = 0;
        while (m.find()) {
            n++;
            assertThat(sql).contains("('" + m.group(1) + "'");
        }
        assertThat(n).isEqualTo(8);
    }
}
