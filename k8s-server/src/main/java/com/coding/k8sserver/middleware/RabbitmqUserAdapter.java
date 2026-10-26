package com.coding.k8sserver.middleware;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.middleware.MiddlewareUserView;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.stereotype.Component;

import java.net.URI;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * RabbitMQ（官方 Cluster Operator）的用户管理——走 Management API。
 *
 * <p>凭据取 operator 生成的 {@code <实例名>-default-user}（{@code username} / {@code password}，
 * 带 administrator tag），vhost 固定 {@code /}。
 */
@Component
public class RabbitmqUserAdapter implements MiddlewareUserAdapter {

    static final String DEFAULT_USER_SECRET_SUFFIX = "-default-user";
    static final String PORT_SUFFIX = ".svc:15672";
    static final String VHOST_PATH = "%2F";
    static final List<String> ROLES = List.of("只读", "读写", "管理员");

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String code() {
        return "rabbitmq";
    }

    @Override
    public List<MiddlewareUserView> list(KubernetesClient client, MiddlewareUserRef ref) {
        Secret usersSecret = UsersSecret.read(client, ref.namespace(), ref.instanceName());
        Map<String, String> platformUsers = UsersSecret.passwords(usersSecret);
        Map<String, String> roles = UsersSecret.roles(usersSecret);

        List<MiddlewareUserView> builtin = new ArrayList<>();
        List<MiddlewareUserView> created = new ArrayList<>();
        for (String username : parseUsers(call(client, ref, "GET", "/api/users", null))) {
            if (platformUsers.containsKey(username)) {
                created.add(view(username, roles.getOrDefault(username, ""), false, ref));
            } else {
                builtin.add(view(username, "管理员", true, ref));
            }
        }
        builtin.addAll(created);
        return builtin;
    }

    @Override
    public String create(KubernetesClient client, MiddlewareUserRef ref, String username, String password, String role) {
        call(client, ref, "PUT", "/api/users/" + username, userBody(password, role));
        call(client, ref, "PUT", "/api/permissions/" + VHOST_PATH + "/" + username, permissions(role));
        return password;
    }

    @Override
    public void drop(KubernetesClient client, MiddlewareUserRef ref, String username) {
        call(client, ref, "DELETE", "/api/users/" + username, null);
    }

    @Override
    public String changePassword(KubernetesClient client, MiddlewareUserRef ref, String username, String newPassword) {
        // 保留原有 tag（内置账号是 administrator，丢了会丢管理权限）
        String tags = tagsOf(call(client, ref, "GET", "/api/users/" + username, null));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("password", newPassword);
        body.put("tags", tags);
        call(client, ref, "PUT", "/api/users/" + username, body);
        return newPassword;
    }

    @Override
    public List<String> roles(MiddlewareUserRef ref) {
        return ROLES;
    }

    // ---------- 纯函数 ----------

    static Map<String, Object> permissions(String role) {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean readOnly = "只读".equals(role);
        m.put("configure", readOnly ? "" : ".*");
        m.put("write", readOnly ? "" : ".*");
        m.put("read", ".*");
        return m;
    }

    static Map<String, Object> userBody(String password, String role) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("password", password);
        m.put("tags", "管理员".equals(role) ? "administrator" : "");
        return m;
    }

    static boolean isInternal(String username) {
        return username == null || username.startsWith("rabbitmq_") || "guest".equals(username);
    }

    /** Management API 的 /api/users 响应：过滤内部账号，保留顺序。 */
    static List<String> parseUsers(String json) {
        List<String> out = new ArrayList<>();
        try {
            for (JsonNode node : JSON.readTree(json)) {
                String name = node.path("name").asText("");
                if (!name.isEmpty() && !isInternal(name)) {
                    out.add(name);
                }
            }
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "解析 RabbitMQ 用户列表失败：" + e.getMessage());
        }
        return out;
    }

    static String tagsOf(String userJson) {
        try {
            JsonNode tags = JSON.readTree(userJson).path("tags");
            if (tags.isArray()) {
                List<String> list = new ArrayList<>();
                tags.forEach(t -> list.add(t.asText()));
                return String.join(",", list);
            }
            return tags.asText("");
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- 内部 ----------

    private static MiddlewareUserView view(String username, String role, boolean builtin,
                                           MiddlewareUserRef ref) {
        MiddlewareUserView v = new MiddlewareUserView();
        v.setUsername(username);
        v.setRole(role);
        v.setBuiltin(builtin);
        v.setSecretName(builtin ? ref.instanceName() + DEFAULT_USER_SECRET_SUFFIX
                : UsersSecret.name(ref.instanceName()));
        v.setSecretKey(username);
        return v;
    }

    private String call(KubernetesClient client, MiddlewareUserRef ref,
                        String method, String path, Object body) {
        String base = "http://" + ref.instanceName() + "." + ref.namespace() + PORT_SUFFIX;
        String[] credentials = credentials(client, ref);
        String auth = Base64.getEncoder().encodeToString(
                (credentials[0] + ":" + credentials[1]).getBytes(StandardCharsets.UTF_8));

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(TIMEOUT)
                .header("Authorization", "Basic " + auth)
                .header("Content-Type", "application/json");
        try {
            HttpRequest request = switch (method) {
                case "GET" -> builder.GET().build();
                case "DELETE" -> builder.DELETE().build();
                default -> builder.method(method, HttpRequest.BodyPublishers.ofString(
                        body == null ? "" : JSON.writeValueAsString(body), StandardCharsets.UTF_8)).build();
            };
            HttpResponse<String> response = CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "RabbitMQ 拒绝了请求（HTTP " + response.statusCode() + "）：" + brief(response.body()));
            }
            return response.body();
        } catch (CloudPlatformException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "调用 RabbitMQ 管理接口失败（" + base + "）：" + e.getMessage());
        }
    }

    private static String[] credentials(KubernetesClient client, MiddlewareUserRef ref) {
        Secret secret = client.secrets().inNamespace(ref.namespace())
                .withName(ref.instanceName() + DEFAULT_USER_SECRET_SUFFIX).get();
        if (secret == null || secret.getData() == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "找不到 RabbitMQ 的管理凭据 Secret：" + ref.instanceName() + DEFAULT_USER_SECRET_SUFFIX);
        }
        return new String[]{decode(secret.getData().get("username")), decode(secret.getData().get("password"))};
    }

    private static String decode(String base64) {
        return base64 == null ? "" : new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
    }

    private static String brief(String body) {
        if (body == null) {
            return "";
        }
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + "…";
    }
}
