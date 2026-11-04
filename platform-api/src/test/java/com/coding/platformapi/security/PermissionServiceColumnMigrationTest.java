package com.coding.platformapi.security;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 网关 spec §8.2：权限行按服务区分 + JWK 生成的权限行（只绑 admin） */
class PermissionServiceColumnMigrationTest {

    private static String sql() throws Exception {
        return Files.readString(Path.of(
                "../platform-data/src/main/resources/db/migration/V2026_11_04_1__permission_service_column.sql"));
    }

    @Test
    void serviceColumnDefaultsToPlatformApi() throws Exception {
        // 默认值 = platform-api：存量行（全是 api 的）无需回填，老代码插入不带 service 也落在 api
        assertThat(sql()).contains("ADD COLUMN service varchar(32) NOT NULL DEFAULT 'platform-api'");
    }

    @Test
    void jwkGenerateRowBelongsToAuthAndOnlyAdmin() throws Exception {
        String sql = sql();
        assertThat(sql).contains("('perm_jwk_generate','API','platform-auth','/internal/jwk/generate','POST','platform:jwk:manage'");
        assertThat(sql).contains("('rp_admin_jwk_generate','builtin_role_admin','perm_jwk_generate')");
        assertThat(sql.split("'builtin_role_").length - 1).isEqualTo(1);
    }
}
