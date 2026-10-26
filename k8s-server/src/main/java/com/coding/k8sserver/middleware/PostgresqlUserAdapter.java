package com.coding.k8sserver.middleware;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.middleware.MiddlewareUserView;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL（CNPG）的用户管理——全声明式，一个 JDBC 连接都不用建。
 *
 * <p>用户 = {@code databaseroles.postgresql.cnpg.io} CR（{@code login} + {@code passwordSecret}
 * + {@code inRoles}）；口令存在每用户一个 {@code kubernetes.io/basic-auth} Secret 里，
 * CNPG 会把它 reconcile 到数据库。删用户只把 {@code ensure} 置 {@code absent}，CR 留着便于回滚。
 *
 * <p>内置账号 = operator 生成的应用账号（Secret {@code <实例名>-app}）。
 */
@Component
public class PostgresqlUserAdapter implements MiddlewareUserAdapter {

    static final String GROUP = "postgresql.cnpg.io";
    static final String VERSION = "v1";
    static final String APP_SECRET_SUFFIX = "-app";
    static final String USER_SECRET_INFIX = "-user-";
    static final List<String> ROLES = List.of("只读", "读写");

    static final CustomResourceDefinitionContext ROLE_CRD = new CustomResourceDefinitionContext.Builder()
            .withGroup(GROUP).withVersion(VERSION)
            .withPlural("databaseroles").withKind("DatabaseRole").withScope("Namespaced")
            .build();

    @Override
    public String code() {
        return "postgresql";
    }

    @Override
    public List<MiddlewareUserView> list(KubernetesClient client, MiddlewareUserRef ref) {
        Map<String, String> roles = UsersSecret.roles(
                UsersSecret.read(client, ref.namespace(), ref.instanceName()));

        List<MiddlewareUserView> out = new ArrayList<>();
        String appUser = appUsername(client, ref);
        out.add(view(appUser, "读写", true, ref));

        for (GenericKubernetesResource cr : client.genericKubernetesResources(ROLE_CRD)
                .inNamespace(ref.namespace()).list().getItems()) {
            Map<String, Object> spec = spec(cr);
            if (!ref.instanceName().equals(spec.get("cluster")) || "absent".equals(spec.get("ensure"))) {
                continue;
            }
            String username = String.valueOf(spec.get("name"));
            out.add(view(username, roles.getOrDefault(username, ""), false, ref));
        }
        return out;
    }

    @Override
    public String create(KubernetesClient client, MiddlewareUserRef ref, String username, String password, String role) {
        String secretName = userSecretName(ref.instanceName(), username);
        client.secrets().inNamespace(ref.namespace())
                .resource(userSecret(secretName, username, password)).createOrReplace();
        client.genericKubernetesResources(ROLE_CRD).inNamespace(ref.namespace())
                .resource(databaseRole(username, ref.instanceName(), secretName, role)).create();
        return password;
    }

    @Override
    public void drop(KubernetesClient client, MiddlewareUserRef ref, String username) {
        var live = client.genericKubernetesResources(ROLE_CRD).inNamespace(ref.namespace())
                .withName(username).get();
        if (live == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "账号不存在：" + username);
        }
        // 只置 absent：CR 留着，误删可以再改回 present
        Map<String, Object> spec = new LinkedHashMap<>(spec(live));
        spec.put("ensure", "absent");
        live.getAdditionalProperties().put("spec", spec);
        client.genericKubernetesResources(ROLE_CRD).inNamespace(ref.namespace()).resource(live).update();
        client.secrets().inNamespace(ref.namespace())
                .withName(userSecretName(ref.instanceName(), username)).delete();
    }

    @Override
    public String changePassword(KubernetesClient client, MiddlewareUserRef ref, String username, String newPassword) {
        String secretName = appUsername(client, ref).equals(username)
                ? ref.instanceName() + APP_SECRET_SUFFIX
                : userSecretName(ref.instanceName(), username);
        Secret current = client.secrets().inNamespace(ref.namespace()).withName(secretName).get();
        if (current == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "找不到凭据 Secret：" + secretName);
        }
        Map<String, String> data = current.getData() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(current.getData());
        data.put("password", Base64.getEncoder().encodeToString(newPassword.getBytes(StandardCharsets.UTF_8)));
        client.secrets().inNamespace(ref.namespace())
                .resource(new SecretBuilder(current).withData(data).build()).createOrReplace();
        return newPassword;
    }

    @Override
    public List<String> roles(MiddlewareUserRef ref) {
        return ROLES;
    }

    // ---------- 纯函数（单测直接断言） ----------

    static GenericKubernetesResource databaseRole(String username, String cluster, String secretName, String role) {
        return roleCr(username, cluster, secretName, role, "present");
    }

    static GenericKubernetesResource absentRole(String username, String cluster) {
        return roleCr(username, cluster, null, null, "absent");
    }

    private static GenericKubernetesResource roleCr(String username, String cluster, String secretName,
                                                    String role, String ensure) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("name", username);
        spec.put("cluster", cluster);
        spec.put("login", true);
        spec.put("ensure", ensure);
        if (secretName != null) {
            spec.put("passwordSecret", Map.of("name", secretName));
        }
        if (role != null) {
            spec.put("inRoles", "只读".equals(role)
                    ? List.of("pg_read_all_data")
                    : List.of("pg_read_all_data", "pg_write_all_data"));
        }
        GenericKubernetesResource cr = new GenericKubernetesResource();
        cr.setApiVersion(GROUP + "/" + VERSION);
        cr.setKind("DatabaseRole");
        ObjectMeta meta = new ObjectMeta();
        meta.setName(username);
        cr.setMetadata(meta);
        cr.setAdditionalProperties(Map.of("spec", spec));
        return cr;
    }

    static Secret userSecret(String secretName, String username, String password) {
        return new SecretBuilder()
                .withNewMetadata().withName(secretName).endMetadata()
                .withType("kubernetes.io/basic-auth")
                .addToStringData("username", username)
                .addToStringData("password", password)
                .build();
    }

    static String userSecretName(String instanceName, String username) {
        return instanceName + USER_SECRET_INFIX + username;
    }

    // ---------- 内部 ----------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> spec(GenericKubernetesResource cr) {
        Object spec = cr.getAdditionalProperties() == null ? null : cr.getAdditionalProperties().get("spec");
        return spec instanceof Map ? (Map<String, Object>) spec : Map.of();
    }

    /** operator 生成的应用账号名（Secret {@code <实例名>-app} 的 username 键） */
    private static String appUsername(KubernetesClient client, MiddlewareUserRef ref) {
        Secret secret = client.secrets().inNamespace(ref.namespace())
                .withName(ref.instanceName() + APP_SECRET_SUFFIX).get();
        String encoded = secret == null || secret.getData() == null ? null : secret.getData().get("username");
        if (encoded == null || encoded.isBlank()) {
            return "app";
        }
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    private static MiddlewareUserView view(String username, String role, boolean builtin,
                                           MiddlewareUserRef ref) {
        MiddlewareUserView v = new MiddlewareUserView();
        v.setUsername(username);
        v.setRole(role);
        v.setBuiltin(builtin);
        v.setSecretName(builtin ? ref.instanceName() + APP_SECRET_SUFFIX
                : userSecretName(ref.instanceName(), username));
        v.setSecretKey("password");
        return v;
    }
}
