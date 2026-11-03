package com.coding.platformapi.security;

import com.coding.platformapi.controllers.OAuthClientController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** OAuth 客户端管理端点的权限行护栏（漏一行 platform-api 启动即 fail-closed） */
class OAuthClientPermissionMigrationTest {

    private static String sql() throws Exception {
        return Files.readString(Path.of(
                "../platform-data/src/main/resources/db/migration/V2026_11_03_2__oauth_client_management.sql"));
    }

    @Test
    void everyEndpointHasPermissionRow() throws Exception {
        String sql = sql();
        String base = OAuthClientController.class.getAnnotation(RequestMapping.class).value()[0];
        int count = 0;
        for (Method m : OAuthClientController.class.getDeclaredMethods()) {
            PostMapping pm = m.getAnnotation(PostMapping.class);
            if (pm != null) {
                count++;
                assertThat(sql).as("缺少权限行：" + base + pm.value()[0]).contains("'" + base + pm.value()[0] + "'");
            }
        }
        assertThat(count).isEqualTo(8);
    }

    @Test
    void pageCodeExistsAndOnlyAdminIsBound() throws Exception {
        String sql = sql();
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
