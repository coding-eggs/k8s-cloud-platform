package com.coding.platformapi.security;

import com.coding.platformapi.controllers.MiddlewareController;
import org.junit.jupiter.api.Test;
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
 * 中间件端点的权限行护栏（用户管理批次）。
 *
 * <p>表驱动授权下漏一行 → {@code PermissionCrossCheckRunner} 启动即失败。2026-10-26 实报过两次：
 * 用户管理六个端点漏配，以及更早在 Milvus 批次里漏掉的 {@code POST /middleware/instance/password}
 * —— 后者一直没暴露，只因为那版没人重启过 platform-api。
 *
 * <p>单测跑不到真实 DB，所以直接解析迁移 SQL，与 controller 的实际映射逐个比对。
 */
class MiddlewarePermissionMigrationTest {

    private static final String MIGRATIONS = "../platform-data/src/main/resources/db/migration/";

    /** 所有带 /middleware/** 权限行的迁移，缺一个都会让下面的比对失真 */
    private static final List<Path> SQL_FILES = List.of(
            Path.of(MIGRATIONS + "V2026_10_22_1__middleware_store.sql"),
            Path.of(MIGRATIONS + "V2026_10_23_2__middleware_form_options_permission.sql"),
            Path.of(MIGRATIONS + "V2026_10_23_3__middleware_connection_permission.sql"),
            Path.of(MIGRATIONS + "V2026_10_23_4__middleware_detail_permissions.sql"),
            Path.of(MIGRATIONS + "V2026_10_23_6__middleware_metrics_permission.sql"),
            Path.of(MIGRATIONS + "V2026_10_26_3__middleware_user_permissions.sql"));

    /** SQL 里 'API' 行的 (METHOD PATH) 集合 */
    private static final Pattern API_ROW = Pattern.compile(
            "'API'\\s*,\\s*'([^']+)'\\s*,\\s*'(POST|GET)'");

    @Test
    void everyMiddlewareEndpointHasAPermissionRow() throws Exception {
        Set<String> rows = new HashSet<>();
        for (Path file : SQL_FILES) {
            String sql = Files.readString(file);
            Matcher matcher = API_ROW.matcher(sql);
            while (matcher.find()) {
                rows.add(matcher.group(2) + " " + matcher.group(1));
            }
        }

        Set<String> endpoints = new HashSet<>();
        String base = String.join("", MiddlewareController.class
                .getAnnotation(RequestMapping.class).value());
        for (Method method : MiddlewareController.class.getDeclaredMethods()) {
            PostMapping post = method.getAnnotation(PostMapping.class);
            if (post != null) {
                endpoints.add("POST " + base + String.join("", post.value()));
            }
        }

        assertThat(endpoints).as("控制器至少要解析出这些端点").isNotEmpty();
        assertThat(rows).as("中间件的每个端点都要有权限行（否则启动期 fail-closed）")
                .containsAll(endpoints);
    }

    @Test
    void userManagementIsReadableByTenantMembersAndWritableOnlyByAdmins() throws Exception {
        String sql = Files.readString(SQL_FILES.get(5));

        // 读：用户列表 / 角色选项 —— tenant:middleware:read，成员也能看
        assertThat(sql).contains("'/middleware/instance/users'");
        assertThat(sql).contains("'/middleware/instance/users/roles'");
        // 写：增 / 改口令 / 删 / 内置口令（Milvus） —— tenant:middleware:write
        assertThat(sql).contains("'/middleware/instance/users/create'");
        assertThat(sql).contains("'/middleware/instance/users/password'");
        assertThat(sql).contains("'/middleware/instance/users/delete'");
        assertThat(sql).contains("'/middleware/instance/password'");
        assertThat(sql).contains("'tenant:middleware:write'");

        // 新权限行必须显式绑角色（不按 code 继承）：admin 与 tenant_admin 全六条，
        // tenant_member 只拿两条读，写操作一行都不能绑给成员
        assertThat(sql).contains("'builtin_role_admin'");
        assertThat(sql).contains("'builtin_role_tenant_admin'");
        assertThat(sql).contains("'builtin_role_tenant_member'");
        assertThat(sql).doesNotContain("rp_tmember_mw_user_create")
                .doesNotContain("rp_tmember_mw_user_password")
                .doesNotContain("rp_tmember_mw_user_delete")
                .doesNotContain("rp_tmember_mw_instance_password");
    }
}
