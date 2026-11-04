package com.coding.auth.internal;

import com.coding.auth.controller.InternalClientController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OAuth 客户端管理端点的权限行护栏。api 转发层删掉后（网关直达 auth），8 行由 V2026_11_04_2 挪到
 * service=platform-auth、resource=/internal/clients/*；漏一行网关一律拒绝、auth 启动自检拒启。
 */
class OAuthClientPermissionMigrationTest {

    private static String sql(String file) throws Exception {
        return Files.readString(Path.of("../platform-data/src/main/resources/db/migration/" + file));
    }

    @Test
    void everyInternalEndpointIsMovedToAuth() throws Exception {
        String sql = sql("V2026_11_04_2__oauth_client_rows_to_auth.sql");
        String base = InternalClientController.class.getAnnotation(RequestMapping.class).value()[0];
        int count = 0;
        for (Method m : InternalClientController.class.getDeclaredMethods()) {
            PostMapping pm = m.getAnnotation(PostMapping.class);
            if (pm != null) {
                count++;
                String path = base + pm.value()[0];
                String id = "perm_oauth_client_" + pm.value()[0].substring(1).replace('-', '_');
                assertThat(sql).as("缺少挪行：" + path)
                        .contains("SET service = 'platform-auth', resource = '" + path + "' WHERE id = '" + id + "';");
            }
        }
        assertThat(count).isEqualTo(8);
        // 只有这 8 条 UPDATE：页面行与角色绑定不动（注释里提到它们不算）
        String statements = String.join("\n", sql.lines().filter(l -> !l.startsWith("--")).toList());
        assertThat(statements).doesNotContain("perm_page_oauth_client").doesNotContain("platform_role_permission");
        assertThat(statements.chars().filter(c -> c == ';').count()).isEqualTo(8);
    }

    @Test
    void pageCodeExistsAndOnlyAdminIsBound() throws Exception {
        String sql = sql("V2026_11_03_2__oauth_client_management.sql");
        assertThat(sql).contains("'platform:page:oauth-client'");
        Matcher m = Pattern.compile("[(]'rp_[a-z_]+','([a-z_]+)'").matcher(sql);
        int rows = 0;
        while (m.find()) {
            rows++;
            assertThat(m.group(1)).isEqualTo("builtin_role_admin");
        }
        assertThat(rows).isEqualTo(9);
        assertThat(sql).contains("JSON_SET(client_settings, '$.\"platform.client.builtin\"', true)")
                .contains("WHERE client_id = 'platform-web-client'");
    }
}
