package com.coding.k8sserver.middleware;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.middleware.MiddlewareUserView;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MySQL（Percona PS）的用户管理——唯一走 Pod exec 的一家。
 *
 * <p>ps-operator 的 CR 里没有声明式用户字段（只有 {@code secretsName}），所以只能 SQL：
 * 口令通过 stdin 喂给 {@code MYSQL_PWD}（见 {@link PodExec}），SQL 只带用户名与授权。
 *
 * <p>内置账号 {@code root} 的口令写回 operator 的 Secret {@code <实例名>-secrets}（键 {@code root}）；
 * 平台建的账号在 {@code <实例名>-users}（见 {@link UsersSecret}）。
 */
@Component
public class MysqlUserAdapter implements MiddlewareUserAdapter {

    static final String BUILTIN = "root";
    static final String CREDENTIAL_SECRET_SUFFIX = "-secrets";
    static final List<String> ROLES = List.of("只读", "读写");
    static final Set<String> SYSTEM_USERS = Set.of("mysql.sys", "mysql.session", "mysql.infoschema");

    @Override
    public String code() {
        return "mysql";
    }

    @Override
    public List<MiddlewareUserView> list(KubernetesClient client, MiddlewareUserRef ref) {
        Secret usersSecret = UsersSecret.read(client, ref.namespace(), ref.instanceName());
        Map<String, String> platformUsers = UsersSecret.passwords(usersSecret);
        Map<String, String> roles = UsersSecret.roles(usersSecret);

        List<MiddlewareUserView> out = new ArrayList<>();
        out.add(view(BUILTIN, "管理员", true, ref));
        for (String username : queryUsers(client, ref)) {
            if (BUILTIN.equals(username) || SYSTEM_USERS.contains(username)
                    || !platformUsers.containsKey(username)) {
                continue;   // 系统账号与别人手工建的账号都不接管
            }
            out.add(view(username, roles.getOrDefault(username, ""), false, ref));
        }
        return out;
    }

    @Override
    public void create(KubernetesClient client, MiddlewareUserRef ref, String username, String password, String role) {
        requireNotBuiltin(username);
        execSql(client, ref, createSql(username, password, role));
    }

    @Override
    public void drop(KubernetesClient client, MiddlewareUserRef ref, String username) {
        requireNotBuiltin(username);
        execSql(client, ref, dropSql(username));
    }

    @Override
    public void changePassword(KubernetesClient client, MiddlewareUserRef ref, String username, String newPassword) {
        execSql(client, ref, alterPasswordSql(username, newPassword));
        if (BUILTIN.equals(username)) {
            // 内置账号的"事实来源"是 operator 的 Secret，改完要写回去
            writeCredentialSecret(client, ref, BUILTIN, newPassword);
        }
    }

    @Override
    public List<String> roles(MiddlewareUserRef ref) {
        return ROLES;
    }

    // ---------- SQL 拼装（纯函数，单测直接断言） ----------

    static String createSql(String username, String password, String role) {
        String user = quote(username);
        String grant = "只读".equals(role) ? "GRANT SELECT ON *.* TO " : "GRANT ALL PRIVILEGES ON *.* TO ";
        return "CREATE USER " + user + "@'%' IDENTIFIED BY " + quote(password)
                + "; " + grant + user + "@'%'";
    }

    static String dropSql(String username) {
        return "DROP USER " + quote(username) + "@'%'";
    }

    static String alterPasswordSql(String username, String password) {
        return "ALTER USER " + quote(username) + "@'%' IDENTIFIED BY " + quote(password);
    }

    /** 单引号字符字面量：反斜杠先转义，单引号用两个单引号——顺序不能反。 */
    static String quote(String value) {
        String escaped = value == null ? "" : value.replace("\\", "\\\\").replace("'", "''");
        return "'" + escaped + "'";
    }

    static void requireNotBuiltin(String username) {
        if (BUILTIN.equals(username)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "内置账号不允许删除或重建：" + BUILTIN);
        }
        if (SYSTEM_USERS.contains(username)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "系统账号不允许操作：" + username);
        }
    }

    /** mysql -N -B 的输出：每行一个用户名，去空行、去重、丢掉 MySQL 自己的系统账号。 */
    static List<String> parseUsers(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        return output.lines().map(String::trim)
                .filter(l -> !l.isEmpty() && !SYSTEM_USERS.contains(l))
                .distinct().toList();
    }

    // ---------- 执行 ----------

    private List<String> queryUsers(KubernetesClient client, MiddlewareUserRef ref) {
        return parseUsers(execSql(client, ref,
                "SELECT user FROM mysql.user WHERE host = '%'"));
    }

    /**
     * 跑一条 SQL：口令从 stdin 读进 {@code MYSQL_PWD}，SQL 作为 {@code $1} 传参。
     */
    private String execSql(KubernetesClient client, MiddlewareUserRef ref, String sql) {
        String password = readRootPassword(client, ref);
        return PodExec.run(client, ref.namespace(), ref.instanceName() + "-mysql-0", "mysql",
                password + "\n",
                "sh", "-c", "read -r P; export MYSQL_PWD=\"$P\"; exec mysql -uroot -N -B -e \"$1\"", "--", sql);
    }

    private static String readRootPassword(KubernetesClient client, MiddlewareUserRef ref) {
        Secret secret = client.secrets().inNamespace(ref.namespace())
                .withName(ref.instanceName() + CREDENTIAL_SECRET_SUFFIX).get();
        String encoded = secret == null || secret.getData() == null ? null : secret.getData().get(BUILTIN);
        if (encoded == null || encoded.isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "读不到 MySQL 的 root 口令（Secret " + ref.instanceName() + CREDENTIAL_SECRET_SUFFIX + "）");
        }
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    /** 改内置账号口令后写回 operator 的 Secret（保留其它键与元数据）。 */
    private static void writeCredentialSecret(KubernetesClient client, MiddlewareUserRef ref,
                                              String key, String value) {
        String name = ref.instanceName() + CREDENTIAL_SECRET_SUFFIX;
        Secret current = client.secrets().inNamespace(ref.namespace()).withName(name).get();
        Map<String, String> data = current == null || current.getData() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(current.getData());
        data.put(key, Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
        Secret target = current == null
                ? new SecretBuilder().withNewMetadata().withName(name).withNamespace(ref.namespace())
                        .endMetadata().withData(data).build()
                : new SecretBuilder(current).withData(data).build();
        client.secrets().inNamespace(ref.namespace()).resource(target).createOrReplace();
    }

    private static MiddlewareUserView view(String username, String role, boolean builtin,
                                           MiddlewareUserRef ref) {
        MiddlewareUserView v = new MiddlewareUserView();
        v.setUsername(username);
        v.setRole(role);
        v.setBuiltin(builtin);
        v.setSecretName(builtin ? ref.instanceName() + CREDENTIAL_SECRET_SUFFIX : UsersSecret.name(ref.instanceName()));
        v.setSecretKey(username);
        return v;
    }
}
