package com.coding.k8sserver.middleware;

import com.coding.common.exception.CloudPlatformException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MySQL 用户管理的纯函数部分：SQL 拼装 + 内置/系统账号白名单。
 *
 * <p>SQL 字符串会被送进 `sh -c 'mysql -e "$1"'`，所以用户名与口令**必须**在同一层
 * 完成转义 —— 这里逐条钉住，避免适配器里再拼一次。
 */
class MysqlUserAdapterTest {

    @Test
    void createStatementGrantsTheMappedRole() {
        assertThat(MysqlUserAdapter.createSql("alice", "Rw-pass-123", "读写"))
                .contains("CREATE USER 'alice'@'%' IDENTIFIED BY 'Rw-pass-123'")
                .contains("GRANT ALL PRIVILEGES ON *.* TO 'alice'@'%'");
        assertThat(MysqlUserAdapter.createSql("bob", "pw-123456", "只读"))
                .contains("GRANT SELECT ON *.* TO 'bob'@'%'");
    }

    @Test
    void dropStatementTargetsHostPercent() {
        assertThat(MysqlUserAdapter.dropSql("alice")).isEqualTo("DROP USER 'alice'@'%'");
    }

    @Test
    void alterStatementsArePerUser() {
        assertThat(MysqlUserAdapter.alterPasswordSql("alice", "pw-123456"))
                .isEqualTo("ALTER USER 'alice'@'%' IDENTIFIED BY 'pw-123456'");
    }

    @Test
    void quotesCannotEscapeTheStringLiteral() {
        // 口令里的单引号与反斜杠都要被吃掉，否则就是一条注入语句
        String sql = MysqlUserAdapter.alterPasswordSql("alice", "a'b\\c");
        assertThat(sql).doesNotContain("'a'b");
        assertThat(sql).contains("a''b");
    }

    @Test
    void refusesDroppingOrOverwritingBuiltinAndSystemUsers() {
        assertThatThrownBy(() -> MysqlUserAdapter.requireNotBuiltin("root"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("内置账号");
        assertThatThrownBy(() -> MysqlUserAdapter.requireNotBuiltin("mysql.sys"))
                .hasMessageContaining("系统账号");
    }

    @Test
    void parsesTheUserListOutput() {
        assertThat(MysqlUserAdapter.parseUsers("root\nalice\nmysql.sys\n\nbob"))
                .containsExactly("root", "alice", "bob");
    }
}
