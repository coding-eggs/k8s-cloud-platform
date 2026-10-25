package com.coding.platformapi.app;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迁移文件的内容检查（没有 Flyway，也没法在单测里连库）。
 * 钉住：四张表、页面码、两类权限码、角色绑定都必须在这个文件里。
 */
class ApplicationMigrationTest {

    private static final String SQL = read();

    private static String read() {
        try {
            // 与 ObjectStoragePermissionMigrationTest 同惯例：测试的工作目录是模块目录
            return Files.readString(Path.of(
                    "../platform-data/src/main/resources/db/migration/V2026_10_25_7__application.sql"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void createsFourTables() {
        assertThat(SQL).contains("CREATE TABLE IF NOT EXISTS platform_application ");
        assertThat(SQL).contains("CREATE TABLE IF NOT EXISTS platform_application_revision");
        assertThat(SQL).contains("CREATE TABLE IF NOT EXISTS platform_application_operation");
        assertThat(SQL).contains("CREATE TABLE IF NOT EXISTS platform_application_resource");
    }

    @Test
    void declaresPageCodeAndPermissionCodes() {
        assertThat(SQL).contains("tenant:page:application");
        assertThat(SQL).contains("tenant:application:read");
        assertThat(SQL).contains("tenant:application:write");
    }

    @Test
    void bindsWriteCodeToAdminAndTenantAdminOnly() {
        assertThat(SQL).contains("'builtin_role_admin'");
        assertThat(SQL).contains("'builtin_role_tenant_admin'");
        assertThat(SQL).doesNotContain("'rp_tenant_member_application_write'");
    }

    @Test
    void coversEveryEndpointOfTheNewModule() {
        List<String> endpoints = List.of(
                "/application/list", "/application/get", "/application/create", "/application/update",
                "/application/delete", "/application/preview", "/application/deploy",
                "/application/operation/get", "/application/revision/list", "/application/options");
        assertThat(SQL).contains(endpoints.toArray(String[]::new));
    }
}
