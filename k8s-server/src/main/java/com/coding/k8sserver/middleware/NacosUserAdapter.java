package com.coding.k8sserver.middleware;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.middleware.MiddlewareUserView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nacos（chart 1.2.0 / Nacos 3.x）的用户管理——只用 v3 auth API。
 *
 * <p>前提：实例创建时必须勾「开启鉴权」（`nacos.auth.enabled=true`），否则没有账号体系。
 * 管理员凭据在 Secret {@code <实例名>-nacos-admin}（{@code username} / {@code password}），
 * 平台创建实例时生成。
 *
 * <p>⚠️ Nacos 3.x 的管理员口令初始化路径按小版本有差异，**真集群验收前不要对外宣称可用**；
 * 验不通就把 nacos 从 `SUPPORTED_CODES` 去掉（UI 自动不显示用户页签）。
 * 角色只给「管理员」：只读/读写要靠 role + permission 组合，属于本批之外。
 */
@Component
public class NacosUserAdapter implements MiddlewareUserAdapter {

    static final String ADMIN_SECRET_SUFFIX = "-nacos-admin";
    static final String BUILTIN = "nacos";
    static final List<String> ROLES = List.of("管理员");
    static final int PORT = 8848;

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String code() {
        return "nacos";
    }

    @Override
    public List<MiddlewareUserView> list(KubernetesClient client, MiddlewareUserRef ref) {
        Map<String, String> platformUsers = UsersSecret.passwords(
                UsersSecret.read(client, ref.namespace(), ref.instanceName()));
        String token = login(client, ref);

        List<MiddlewareUserView> out = new ArrayList<>();
        out.add(view(BUILTIN, "管理员", true, ref));
        for (String username : users(client, ref, token)) {
            if (BUILTIN.equals(username) || !platformUsers.containsKey(username)) {
                continue;
            }
            out.add(view(username, "管理员", false, ref));
        }
        return out;
    }

    @Override
    public String create(KubernetesClient client, MiddlewareUserRef ref, String username, String password, String role) {
        String token = login(client, ref);
        call(client, ref, "POST", "/nacos/v3/auth/user",
                form(Map.of("username", username, "password", password, "accessToken", token)));
        return password;
    }

    @Override
    public void drop(KubernetesClient client, MiddlewareUserRef ref, String username) {
        String token = login(client, ref);
        call(client, ref, "DELETE", "/nacos/v3/auth/user?username=" + encode(username)
                + "&accessToken=" + encode(token), null);
    }

    @Override
    public String changePassword(KubernetesClient client, MiddlewareUserRef ref, String username, String newPassword) {
        String token = login(client, ref);
        call(client, ref, "PUT", "/nacos/v3/auth/user",
                form(Map.of("username", username, "newPassword", newPassword, "accessToken", token)));
        return newPassword;
    }

    @Override
    public List<String> roles(MiddlewareUserRef ref) {
        return ROLES;
    }

    // ---------- 纯函数 ----------

    static String form(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        fields.forEach((k, v) -> {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(encode(k)).append('=').append(encode(v == null ? "" : v));
        });
        return sb.toString();
    }

    static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    static String baseUrl(String instanceName, String namespace) {
        // chart 把 Service 名字写死成 nacos-cs，实例之间靠命名空间隔离
        return "http://nacos-cs." + namespace + ".svc:" + PORT;
    }

    static String accessTokenOf(String body) {
        try {
            JsonNode json = JSON.readTree(body);
            JsonNode token = json.path("accessToken");
            if (token.isMissingNode() || token.asText().isBlank()) {
                token = json.path("data").path("accessToken");
            }
            return token.asText("");
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "解析 Nacos 登录响应失败：" + e.getMessage());
        }
    }

    static List<String> parseUsers(String body) {
        List<String> out = new ArrayList<>();
        try {
            JsonNode json = JSON.readTree(body);
            JsonNode list = json.path("data").path("pageItems");
            if (list.isMissingNode()) {
                list = json.path("data");
            }
            if (list.isArray()) {
                list.forEach(n -> {
                    String name = n.path("username").asText("");
                    if (!name.isBlank()) {
                        out.add(name);
                    }
                });
            }
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "解析 Nacos 用户列表失败：" + e.getMessage());
        }
        return out;
    }

    // ---------- 内部 ----------

    private List<String> users(KubernetesClient client, MiddlewareUserRef ref, String token) {
        return parseUsers(call(client, ref, "GET",
                "/nacos/v3/auth/user/list?pageNo=1&pageSize=200&accessToken=" + encode(token), null));
    }

    private String login(KubernetesClient client, MiddlewareUserRef ref) {
        String[] admin = admin(client, ref);
        String body = call(client, ref, "POST", "/nacos/v3/auth/user/login",
                form(Map.of("username", admin[0], "password", admin[1])));
        String token = accessTokenOf(body);
        if (token.isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "Nacos 登录没有返回 accessToken");
        }
        return token;
    }

    private static String[] admin(KubernetesClient client, MiddlewareUserRef ref) {
        Secret secret = client.secrets().inNamespace(ref.namespace())
                .withName(ref.instanceName() + ADMIN_SECRET_SUFFIX).get();
        if (secret == null || secret.getData() == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "实例没有开启鉴权（找不到 Secret " + ref.instanceName() + ADMIN_SECRET_SUFFIX + "）");
        }
        return new String[]{decode(secret.getData().get("username")), decode(secret.getData().get("password"))};
    }

    private String call(KubernetesClient client, MiddlewareUserRef ref,
                        String method, String path, String formBody) {
        String base = baseUrl(ref.instanceName(), ref.namespace());
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(TIMEOUT);
        try {
            HttpRequest request = switch (method) {
                case "GET" -> builder.GET().build();
                case "DELETE" -> builder.DELETE().build();
                default -> builder.header("Content-Type", "application/x-www-form-urlencoded")
                        .method(method, HttpRequest.BodyPublishers.ofString(
                                formBody == null ? "" : formBody, StandardCharsets.UTF_8)).build();
            };
            HttpResponse<String> response = CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "Nacos 拒绝了请求（HTTP " + response.statusCode() + "）：" + MilvusRest.brief(response.body()));
            }
            return response.body();
        } catch (CloudPlatformException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "调用 Nacos 接口失败（" + base + "）：" + e.getMessage());
        }
    }

    private static MiddlewareUserView view(String username, String role, boolean builtin,
                                           MiddlewareUserRef ref) {
        MiddlewareUserView v = new MiddlewareUserView();
        v.setUsername(username);
        v.setRole(role);
        v.setBuiltin(builtin);
        v.setSecretName(builtin ? ref.instanceName() + ADMIN_SECRET_SUFFIX : UsersSecret.name(ref.instanceName()));
        v.setSecretKey(username);
        return v;
    }

    private static String decode(String base64) {
        return base64 == null ? "" : new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
    }

    static Map<String, String> formOf(String... kv) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out.put(kv[i], kv[i + 1]);
        }
        return out;
    }
}
