package com.coding.k8sserver.middleware;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL 用 CNPG 的 DatabaseRole CRD 做用户：全部是声明式数据，不连数据库。
 */
class PostgresqlUserAdapterTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> spec(GenericKubernetesResource cr) {
        return (Map<String, Object>) cr.getAdditionalProperties().get("spec");
    }

    @Test
    void databaseRoleBindsLoginToThePasswordSecret() {
        var cr = PostgresqlUserAdapter.databaseRole("alice", "mw-pg-abcdefgh", "alice-pw", "只读");

        assertThat(cr.getKind()).isEqualTo("DatabaseRole");
        assertThat(cr.getApiVersion()).isEqualTo("postgresql.cnpg.io/v1");
        assertThat(spec(cr))
                .containsEntry("name", "alice")
                .containsEntry("cluster", "mw-pg-abcdefgh")
                .containsEntry("login", true)
                .containsEntry("ensure", "present");
        assertThat((Map<String, Object>) spec(cr).get("passwordSecret"))
                .containsEntry("name", "alice-pw");
    }

    @Test
    void readOnlyGrantsTheReadRoleAndReadWriteAddsTheWriteRole() {
        assertThat((List<String>) spec(PostgresqlUserAdapter.databaseRole("a", "c", "s", "只读")).get("inRoles"))
                .containsExactly("pg_read_all_data");
        assertThat((List<String>) spec(PostgresqlUserAdapter.databaseRole("a", "c", "s", "读写")).get("inRoles"))
                .containsExactly("pg_read_all_data", "pg_write_all_data");
    }

    @Test
    void absentMarksDeletionWithoutDroppingTheRecord() {
        var cr = PostgresqlUserAdapter.absentRole("alice", "mw-pg-abcdefgh");

        assertThat(spec(cr)).containsEntry("ensure", "absent");
    }

    @Test
    void userSecretIsBasicAuthWithUsernameAndPassword() {
        var secret = PostgresqlUserAdapter.userSecret("alice-pw", "alice", "pw-123456");

        assertThat(secret.getType()).isEqualTo("kubernetes.io/basic-auth");
        assertThat(secret.getStringData())
                .containsEntry("username", "alice")
                .containsEntry("password", "pw-123456");
    }

    @Test
    void userSecretsAreScopedToTheInstance() {
        assertThat(PostgresqlUserAdapter.userSecretName("mw-pg-abcdefgh", "alice"))
                .isEqualTo("mw-pg-abcdefgh-user-alice");
    }

    /**
     * 「管理员」= 数据库里的 superuser（与 ES / Milvus 的管理员口径一致）。
     * CNPG 的 DatabaseRole 有 `superuser` 字段；建它的账号自己得是 superuser，
     * 而默认账号 app 在 bootstrap 时已提为 SUPERUSER。
     */
    @Test
    void adminRoleCreatesASuperuserWithoutInRoles() {
        var spec = spec(PostgresqlUserAdapter.databaseRole("alice", "c", "s", "管理员"));

        assertThat(spec).containsEntry("superuser", true);
        assertThat(spec).doesNotContainKey("inRoles");
    }
}
