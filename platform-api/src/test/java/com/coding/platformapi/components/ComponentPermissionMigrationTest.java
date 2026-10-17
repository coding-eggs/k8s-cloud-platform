package com.coding.platformapi.components;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review Focus #3 的唯一自动化护栏：**`/cluster/component-flags` 必须两行同一路径，
 * 且租户两个内置角色都要绑**。
 *
 * <p>为什么要读 SQL 文件：这一格没人能靠单测跑到 —— 权限行是手工重放的，而
 * {@code PermissionCrossCheckRunner} 只查"有没有行"、不查"租户角色拿不拿得到"。
 * 少写租户那一行，症状是租户进 CI 页/网格页 403（而"能不能跑 CI"正是他要的答案）。
 *
 * <p>Maven 跑测试时的工作目录是模块目录，故用相对路径取同级模块的迁移文件。
 */
class ComponentPermissionMigrationTest {

    private static final Path SQL =
            Path.of("../platform-data/src/main/resources/db/migration/V2026_10_17_4__cluster_components.sql");

    @Test
    void theFlagsEndpointHasTwoRowsOnOnePathWithTenantRolesBound() throws Exception {
        String sql = Files.readString(SQL);

        // 两行同一路径（管理员 + 租户只读码）
        assertThat(count(sql, "'/cluster/component-flags'")).isEqualTo(2);
        assertThat(sql).contains("tenant:cluster:capability:view");

        // 两个租户角色都要显式绑（不按 code 前缀继承）
        assertThat(sql).contains("builtin_role_tenant_admin");
        assertThat(sql).contains("builtin_role_tenant_member");
    }

    @Test
    void theOtherTwoEndpointsUseThePlatformManageCode() throws Exception {
        String sql = Files.readString(SQL);

        assertThat(sql).contains("'/cluster/components'").contains("'/cluster/components/refresh'");
        assertThat(count(sql, "platform:cluster:manage")).isGreaterThanOrEqualTo(3);
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
