package com.coding.platformapi.security;

import com.coding.platformapi.controllers.ApplicationController;
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
 * 应用编排端点的权限行护栏（表驱动授权：漏一行 platform-api 启动即 fail-closed）。
 * 与 {@link ObjectStoragePermissionMigrationTest} 同一套路 —— 直接解析迁移 SQL 比对控制器映射。
 */
class ApplicationPermissionMigrationTest {

    @Test
    void everyEndpointHasPermissionRow() throws Exception {
        Set<String> endpoints = new HashSet<>();
        Class<?> controller = ApplicationController.class;
        String base = controller.getAnnotation(RequestMapping.class).value()[0];
        for (Method m : controller.getDeclaredMethods()) {
            PostMapping pm = m.getAnnotation(PostMapping.class);
            if (pm != null) {
                endpoints.add(base + pm.value()[0]);
            }
        }

        // 旧画布接口在 V2026_10_25_7，去画布版的发布接口在 V2026_11_02_11，回滚在 V2026_11_02_12，依赖在 V2026_11_02_13，
        // 修改外部连接在 V2026_11_03_1，下线在 V2026_11_06_1
        String sql = Files.readString(Path.of(
                "../platform-data/src/main/resources/db/migration/V2026_10_25_7__application.sql"))
                + Files.readString(Path.of("../platform-data/src/main/resources/db/migration/"
                + "V2026_11_02_11__application_release_permissions.sql"))
                + Files.readString(Path.of("../platform-data/src/main/resources/db/migration/"
                + "V2026_11_02_12__application_release_rollback_permission.sql"))
                + Files.readString(Path.of("../platform-data/src/main/resources/db/migration/"
                + "V2026_11_02_13__application_dependency_permissions.sql"))
                + Files.readString(Path.of("../platform-data/src/main/resources/db/migration/"
                + "V2026_11_03_1__application_dependency_update_permission.sql"))
                + Files.readString(Path.of("../platform-data/src/main/resources/db/migration/"
                + "V2026_11_06_1__application_release_offline_permission.sql"));

        assertThat(endpoints).isNotEmpty();
        for (String endpoint : endpoints) {
            assertThat(sql).as("缺少权限行：" + endpoint).contains("'" + endpoint + "'");
        }
    }
}
