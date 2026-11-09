package com.coding.platformapi.services;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 灰度端点的权限行是手工重放的 SQL，{@code PermissionCrossCheckRunner} 只在启动时查「有没有行」；
 * 这里钉住：五个端点都有行、码对、角色按已持有的同类行回填（新行不会按 code 自动继承）。
 * Maven 跑测试时工作目录是模块目录，故用相对路径取同级模块的迁移文件。
 */
class AppCanaryPermissionMigrationTest {

    private static final Path SQL =
            Path.of("../platform-data/src/main/resources/db/migration/V2026_11_09_2__app_canary.sql");

    @Test
    void everyCanaryEndpointHasARowWithTheRightCode() throws Exception {
        String sql = Files.readString(SQL);
        for (String op : new String[]{"start", "update", "promote", "abort"}) {
            assertThat(sql).as(op).containsPattern(
                    "'perm_app_canary_" + op + "',\\s*'API',\\s*'/application/canary/" + op
                            + "',\\s*'POST',\\s*'tenant:application:write'");
        }
        assertThat(sql).containsPattern(
                "'perm_app_canary_metrics',\\s*'API',\\s*'/application/canary/metrics',\\s*'POST',\\s*'tenant:application:read'");
    }

    @Test
    void rolesAreBackfilledFromTheReleaseAndOverviewRows() throws Exception {
        String sql = Files.readString(SQL);
        assertThat(sql).contains("'perm_app_release_create'").contains("'perm_app_overview'");
        assertThat(sql).contains("INSERT INTO platform_role_permission").contains("ON DUPLICATE KEY UPDATE");
    }

    @Test
    void everyLineCommentHasWhitespaceAfterTheDashes() throws Exception {
        // MySQL：`--` 后面不跟空白就不是注释，整个文件重放失败
        // 只看行首注释：注释正文里引用的 `--` 已在注释内，不算
        Matcher m = Pattern.compile("(?m)^\\s*--(?=\\S)").matcher(Files.readString(SQL));
        assertThat(m.find()).as("`--` 后必须跟空白").isFalse();
    }
}
