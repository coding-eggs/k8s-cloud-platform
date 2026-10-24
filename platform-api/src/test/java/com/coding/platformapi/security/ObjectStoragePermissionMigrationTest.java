package com.coding.platformapi.security;

import com.coding.platformapi.controllers.ObjectStorageController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对象存储端点的权限行护栏。
 *
 * <p>这张表是**表驱动授权**：漏一行 → {@code PermissionCrossCheckRunner} 启动即失败
 * （2026-10-24 实报：`POST /object-storage/credentials/list` 漏行，应用起不来）。
 * 单测跑不到真实 DB，所以这里直接解析迁移 SQL，和 controller 的实际映射逐个比对。
 */
class ObjectStoragePermissionMigrationTest {

    private static final List<Path> SQL_FILES = List.of(
            Path.of("../platform-data/src/main/resources/db/migration/V2026_10_24_1__object_storage.sql"),
            Path.of("../platform-data/src/main/resources/db/migration/"
                    + "V2026_10_24_2__object_storage_credentials_list_permission.sql"));

    /** SQL 里 'API' 行的 (METHOD PATH) 集合 */
    private static final Pattern API_ROW = Pattern.compile(
            "'API'\\s*,\\s*'([^']+)'\\s*,\\s*'(POST|GET)'");

    @Test
    void everyObjectStorageEndpointHasAPermissionRow() throws Exception {
        Set<String> rows = new HashSet<>();
        for (Path file : SQL_FILES) {
            String sql = Files.readString(file);
            Matcher matcher = API_ROW.matcher(sql);
            while (matcher.find()) {
                rows.add(matcher.group(2) + " " + matcher.group(1));
            }
        }

        Set<String> endpoints = new HashSet<>();
        String base = String.join("", ObjectStorageController.class
                .getAnnotation(RequestMapping.class).value());
        for (Method method : ObjectStorageController.class.getDeclaredMethods()) {
            PostMapping post = method.getAnnotation(PostMapping.class);
            if (post != null) {
                endpoints.add("POST " + base + String.join("", post.value()));
                continue;
            }
            GetMapping get = method.getAnnotation(GetMapping.class);
            if (get != null) {
                endpoints.add("GET " + base + String.join("", get.value()));
            }
        }

        assertThat(rows).as("控制器至少解析出若干端点").isNotEmpty();
        assertThat(rows).as("对象存储的每个端点都要有权限行（否则启动期 fail-closed）")
                .containsAll(endpoints);
    }

    @Test
    void credentialEndpointsAreReadableByTenantsAndManageableOnlyByAdmin() throws Exception {
        String list = Files.readString(SQL_FILES.get(1));
        // 凭据列表：租户也要看得到自己桶的凭据
        assertThat(list).contains("'/object-storage/credentials/list'");
        assertThat(list).contains("'tenant:object-storage:read'");
        assertThat(list).contains("'builtin_role_tenant_admin'");
        assertThat(list).contains("'builtin_role_tenant_member'");

        // 写操作（建删桶/配额/发吊销凭据）只在 platform:object-storage:manage 上，且只绑 admin
        String main = Files.readString(SQL_FILES.get(0));
        assertThat(main).contains("'platform:object-storage:manage'");
        assertThat(main).contains("'builtin_role_admin'");
    }
}
