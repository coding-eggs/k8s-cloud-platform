package com.coding.platformapi.security;

import com.coding.platformapi.controllers.PermissionController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权限点读接口（目录 / 按 URL 反查）的权限行护栏。
 *
 * <p>表驱动授权是 fail-closed 的：新端点漏配权限行，platform-api 启动期交叉校验就会拒启。
 * 这两个接口复用已有的 {@code platform:role:read} 码，只需补行，不需要新码/新绑定。
 */
class PermissionQueryEndpointPermissionMigrationTest {

    private static final String MIGRATION =
            "../platform-data/src/main/resources/db/migration/V2026_11_01_2__permission_catalog_match.sql";

    @Test
    void controller_declares_catalog_and_match() {
        Set<String> endpoints = endpoints();
        assertThat(endpoints).contains("/permission/catalog", "/permission/match");
    }

    @Test
    void migration_has_a_row_for_each_query_endpoint() throws Exception {
        String sql = Files.readString(Path.of(MIGRATION));
        // 只校验本批新增的两个读端点；list/create/update/delete/reload 的行在更早的迁移里
        for (String endpoint : Set.of("/permission/catalog", "/permission/match")) {
            assertThat(sql).as("缺少权限行：" + endpoint).contains("'" + endpoint + "'");
        }
        assertThat(sql).contains("'platform:role:read'");
    }

    private static Set<String> endpoints() {
        Set<String> endpoints = new HashSet<>();
        String base = PermissionController.class.getAnnotation(RequestMapping.class).value()[0];
        for (Method m : PermissionController.class.getDeclaredMethods()) {
            PostMapping pm = m.getAnnotation(PostMapping.class);
            if (pm != null && pm.value().length == 1) endpoints.add(base + pm.value()[0]);
        }
        return endpoints;
    }
}
