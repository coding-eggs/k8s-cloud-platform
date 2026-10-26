package com.coding.k8sserver.middleware;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.middleware.MiddlewareUserView;
import com.fasterxml.jackson.databind.JsonNode;
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

/**
 * Milvus 用户管理（RESTful v2）。
 *
 * <p>改 root 口令沿用 {@link MilvusPasswordClient#update}（详情页的"改密码"也走那条）；
 * 本类补上列表 / 新增 / 删除 / 角色查询。
 *
 * <p><b>认证</b>：开了鉴权的实例，v2 REST 要带 {@code Authorization: Bearer base64(root:口令)}；
 * 凭据从 {@code <实例名>-milvus-auth} 读（拿不到 = 没开鉴权，不带认证头）。
 *
 * <p>角色：实测实例 {@code roles/list} 只有 {@code admin} / {@code public}，而 {@code public}
 * 默认没有任何权限 —— 所以下拉里只给 {@code admin}，将来实例里出现别的角色会自动出现。
 */
@Component
public class MilvusUserAdapter implements MiddlewareUserAdapter {

    static final String BUILTIN = "root";
    static final String AUTH_SECRET_SUFFIX = "-milvus-auth";
    static final String USERS_PATH = "/v2/vectordb/users/list";
    static final String CREATE_PATH = "/v2/vectordb/users/create";
    static final String DROP_PATH = "/v2/vectordb/users/drop";
    static final String ROLES_PATH = "/v2/vectordb/roles/list";
    static final String GRANT_PATH = "/v2/vectordb/roles/grant_role";

    @Override
    public String code() {
        return "milvus";
    }

    @Override
    public List<MiddlewareUserView> list(KubernetesClient client, MiddlewareUserRef ref) {
        Secret usersSecret = UsersSecret.read(client, ref.namespace(), ref.instanceName());
        Map<String, String> platformUsers = UsersSecret.passwords(usersSecret);
        Map<String, String> roles = UsersSecret.roles(usersSecret);

        List<String> names = parseNames(MilvusRest.post(baseUrl(ref.instanceName(), ref.namespace()),
                USERS_PATH, Map.of(), bearer(client, ref)).toString());

        List<MiddlewareUserView> out = new ArrayList<>();
        out.add(view(BUILTIN, "admin", true, ref));
        for (String name : names) {
            if (BUILTIN.equals(name) || !platformUsers.containsKey(name)) {
                continue;   // 别人手工建的账号不接管
            }
            out.add(view(name, roles.getOrDefault(name, ""), false, ref));
        }
        return out;
    }

    @Override
    public String create(KubernetesClient client, MiddlewareUserRef ref, String username, String password, String role) {
        String base = baseUrl(ref.instanceName(), ref.namespace());
        List<String> roles = rolesFrom(client, ref);
        if (!roles.contains(role)) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "角色 " + role + " 在该实例上不可用（实测可选：" + String.join(" / ", roles) + "）");
        }
        MilvusRest.post(base, CREATE_PATH, createBody(username, password), bearer(client, ref));
        MilvusRest.post(base, GRANT_PATH, grantBody(role, username), bearer(client, ref));
        return password;
    }

    @Override
    public void drop(KubernetesClient client, MiddlewareUserRef ref, String username) {
        MilvusRest.post(baseUrl(ref.instanceName(), ref.namespace()), DROP_PATH,
                Map.of("userName", username), bearer(client, ref));
    }

    @Override
    public String changePassword(KubernetesClient client, MiddlewareUserRef ref, String username, String newPassword) {
        String old = BUILTIN.equals(username)
                ? builtinPassword(client, ref)
                : UsersSecret.passwords(UsersSecret.read(client, ref.namespace(), ref.instanceName()))
                        .get(username);
        if (old == null || old.isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "找不到账号 " + username + " 的当前口令");
        }
        MilvusPasswordClient.update(baseUrl(ref.instanceName(), ref.namespace()), username, old, newPassword);
        if (BUILTIN.equals(username)) {
            // 内置账号的"事实来源"是 <实例名>-milvus-auth：不写回，下一次改口令取到的旧口令就是错的
            writePassword(client, ref, ref.instanceName() + AUTH_SECRET_SUFFIX, newPassword);
        }
        return newPassword;
    }

    @Override
    public List<String> roles(KubernetesClient client, MiddlewareUserRef ref) {
        // Milvus 的角色是实例里现查的（实测只有 admin / public，public 无权限不给选）
        return rolesFrom(client, ref);
    }

    List<String> rolesFrom(KubernetesClient client, MiddlewareUserRef ref) {
        return assignableRoles(parseNames(MilvusRest.post(baseUrl(ref.instanceName(), ref.namespace()),
                ROLES_PATH, Map.of(), bearer(client, ref)).toString()));
    }

    // ---------- 纯函数 ----------

    /** {@code public} 默认无权限，不给选。 */
    static List<String> assignableRoles(List<String> all) {
        return all.stream().filter(r -> !"public".equals(r)).toList();
    }

    static Map<String, Object> createBody(String username, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userName", username);
        body.put("password", password);
        return body;
    }

    static Map<String, Object> grantBody(String role, String username) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roleName", role);
        body.put("userName", username);
        return body;
    }

    static String baseUrl(String instanceName, String namespace) {
        return "http://" + instanceName + "-milvus." + namespace + ".svc:19530";
    }

    /** RESTful v2 的响应：{@code {"code":0,"data":["root","alice"]}} —— 取 data 数组 */
    static List<String> parseNames(String responseJson) {
        List<String> out = new ArrayList<>();
        try {
            JsonNode data = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(responseJson).path("data");
            if (data.isArray()) {
                data.forEach(n -> out.add(n.asText()));
            }
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "解析 Milvus 列表失败：" + e.getMessage());
        }
        return out;
    }

    // ---------- 内部 ----------

    /** {@code Base64("root:口令")}；实例没开鉴权时返回 null（REST 不需要认证）。 */
    private static String bearer(KubernetesClient client, MiddlewareUserRef ref) {
        Secret secret = client.secrets().inNamespace(ref.namespace())
                .withName(ref.instanceName() + AUTH_SECRET_SUFFIX).get();
        if (secret == null || secret.getData() == null) {
            return null;
        }
        String user = decode(secret.getData().get("username"));
        String password = decode(secret.getData().get("password"));
        if (password == null || password.isBlank()) {
            return null;
        }
        return Base64.getEncoder().encodeToString(
                ((user == null || user.isBlank() ? BUILTIN : user) + ":" + password)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static String builtinPassword(KubernetesClient client, MiddlewareUserRef ref) {
        Secret secret = client.secrets().inNamespace(ref.namespace())
                .withName(ref.instanceName() + AUTH_SECRET_SUFFIX).get();
        String encoded = secret == null || secret.getData() == null ? null : secret.getData().get("password");
        if (encoded == null || encoded.isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "实例没有开启鉴权（找不到 Secret " + ref.instanceName() + AUTH_SECRET_SUFFIX + "）");
        }
        return decode(encoded);
    }

    private static void writePassword(KubernetesClient client, MiddlewareUserRef ref,
                                      String secretName, String password) {
        Secret current = client.secrets().inNamespace(ref.namespace()).withName(secretName).get();
        if (current == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "找不到凭据 Secret：" + secretName);
        }
        Map<String, String> data = current.getData() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(current.getData());
        data.put("password", Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8)));
        client.secrets().inNamespace(ref.namespace())
                .resource(new SecretBuilder(current).withData(data).build()).createOrReplace();
    }

    private static String decode(String base64) {
        return base64 == null ? null : new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
    }

    private static MiddlewareUserView view(String username, String role, boolean builtin,
                                           MiddlewareUserRef ref) {
        MiddlewareUserView v = new MiddlewareUserView();
        v.setUsername(username);
        v.setRole(role);
        v.setBuiltin(builtin);
        v.setSecretName(builtin ? ref.instanceName() + AUTH_SECRET_SUFFIX : UsersSecret.name(ref.instanceName()));
        v.setSecretKey(username);
        return v;
    }
}
