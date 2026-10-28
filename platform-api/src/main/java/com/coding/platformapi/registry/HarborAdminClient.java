package com.coding.platformapi.registry;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.platformapi.outbound.OutboundProxy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Harbor 的**写入**出站（建项目 / 建删机器人）。与 {@link RegistryHttpClient} 同一套规矩：
 * 显式超时、不跟随重定向、Basic 认证、内网直连（{@link OutboundProxy} 白名单决定代理）。
 *
 * <p>只被 {@code HarborRegistryService} 使用；凭据（管理口令 / robot secret）不落日志。
 */
@Slf4j
@Component
public class HarborAdminClient {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient restClient;

    @Autowired
    public HarborAdminClient(OutboundProxy proxy) {
        HttpClient http = HttpClient.newBuilder()
                .proxy(proxy.selector())
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(READ_TIMEOUT_MS));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** 测试用 */
    HarborAdminClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public record HarborRobot(long id, String name, String secret) {
    }

    /**
     * 确保项目存在并返回 project_id。
     *
     * <p>GET 404 → POST（201）→ 再 GET 取 id（POST 只回 Location，不保证有 body）。
     * 并发建同名项目时 POST 可能 409 —— 再 GET 一次即拿到。
     */
    public long ensureProject(String baseUrl, String user, String pass, String projectName) {
        Long id = projectId(baseUrl, user, pass, projectName);
        if (id != null) {
            return id;
        }
        try {
            restClient.post().uri(baseUrl + "/api/v2.0/projects")
                    .header(HttpHeaders.AUTHORIZATION, basic(user, pass))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("project_name", projectName, "public", false))
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 409) {
                throw failure("创建 Harbor 项目失败", projectName, e);
            }
        }
        Long created = projectId(baseUrl, user, pass, projectName);
        if (created == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "Harbor 项目创建后仍查不到：" + projectName);
        }
        return created;
    }

    /** 只查项目 id（404 → null，**不创建**）：删租户回收账号时用，避免顺手把项目建出来 */
    /**
     * 把项目设成「公开读」：匿名可以 pull，push 仍然要账号。
     *
     * <p>平台的 {@code platform} 项目放的是组件/中间件镜像。设成公开读之后，节点匿名拉取即可，
     * 不用给每个命名空间发 {@code imagePullSecret}，也不用给平台机器人分发凭据 ——
     * 少一条每次安装都要维护的链路。代价是**能访问到 registry 的人都能拉**（内网 Harbor 可接受）。
     */
    public void ensurePublicProject(String baseUrl, String user, String pass,
                                    long projectId, String projectName) {
        try {
            restClient.put().uri(baseUrl + "/api/v2.0/projects/" + projectId)
                    .header(HttpHeaders.AUTHORIZATION, basic(user, pass))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("metadata", Map.of("public", "true")))
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw failure("设置 Harbor 项目为公开读失败", projectName, e);
        }
    }

    public Long findProjectId(String baseUrl, String user, String pass, String projectName) {
        return projectId(baseUrl, user, pass, projectName);
    }

    /** 建机器人账号（project 级，push+pull，不过期）。返回的 secret **只出现这一次**。 */
    public HarborRobot createRobot(String baseUrl, String user, String pass, String projectName) {
        Map<String, Object> body = Map.of(
                "name", HarborNaming.ROBOT_NAME,
                "level", "project",
                "duration", -1,
                "permissions", List.of(Map.of(
                        "kind", "project",
                        "namespace", projectName,
                        "access", List.of(
                                Map.of("resource", "repository", "action", "push"),
                                Map.of("resource", "repository", "action", "pull"),
                                // ⚠️ 平台要用这个 robot **浏览**镜像仓库：列仓库走的是
                                //    GET /projects/{name}/repositories，Harbor 要求的动作是 `list`，
                                //    只给 pull/push 会 403（2026-10-28 用户撞到）
                                Map.of("resource", "repository", "action", "list"),
                                // 第三层列 tag 走 artifacts 接口，同理要 artifact:list
                                Map.of("resource", "artifact", "action", "list")))));
        try {
            String raw = restClient.post().uri(baseUrl + "/api/v2.0/robots")
                    .header(HttpHeaders.AUTHORIZATION, basic(user, pass))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve().body(String.class);
            JsonNode node = JSON.readTree(raw == null ? "{}" : raw);
            String secret = node.path("secret").asString(null);
            if (secret == null || secret.isBlank()) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "Harbor 机器人账号创建成功但没有返回 secret（无法再取回，请重建）");
            }
            return new HarborRobot(node.path("id").asLong(), node.path("name").asString(null), secret);
        } catch (RestClientResponseException e) {
            throw failure("创建 Harbor 机器人账号失败", projectName, e);
        } catch (CloudPlatformException e) {
            throw e;
        } catch (Exception e) {
            // ⚠️ 不带 e.getMessage()：Jackson 的解析异常里会带被解析的原文（含一次性 secret），
            // 这条消息会被写进台账并打日志（评审 Minor #3）
            throw new CloudPlatformException(EnumResponseType.ERROR.getCode(),
                    "解析 Harbor 机器人账号响应失败（响应体可能含一次性 secret，已省略）", e);
        }
    }

    /** 删机器人账号；404 视为已经不在（幂等） */
    public void deleteRobot(String baseUrl, String user, String pass, long robotId) {
        try {
            restClient.method(HttpMethod.DELETE).uri(baseUrl + "/api/v2.0/robots/" + robotId)
                    .header(HttpHeaders.AUTHORIZATION, basic(user, pass))
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return;
            }
            throw failure("删除 Harbor 机器人账号失败", String.valueOf(robotId), e);
        }
    }

    /** 按 Harbor 内的完整名字删机器人（台账丢了 robot_id 时的兜底） */
    public void deleteRobotByName(String baseUrl, String user, String pass, long projectId,
                                  String harborRobotName) {
        for (long id : robotIds(baseUrl, user, pass, projectId, harborRobotName)) {
            deleteRobot(baseUrl, user, pass, id);
        }
    }

    /**
     * 用 **robot 自己的凭据**列一下它 project 里的仓库（只取 1 条）。
     *
     * <p>为什么值得多发一次请求：robot 的权限是**建的时候定死的**，凭据行看起来却一切正常；
     * 权限不够要等用户点开镜像仓库才暴露（"凭据不通过，或该凭据无权访问此仓库"）。
     * 这里提前问一次，同步就能自愈/至少把真实原因写进台账。
     *
     * @return {@code null} = 可用；否则是失败描述（如 {@code HTTP 403}）
     */
    public String probeRobot(String baseUrl, String project, String robotUser, String robotSecret) {
        try {
            restClient.get()
                    .uri(baseUrl + "/api/v2.0/projects/" + project + "/repositories?page_size=1")
                    .header(HttpHeaders.AUTHORIZATION, basic(robotUser, robotSecret))
                    .retrieve().body(String.class);
            return null;
        } catch (RestClientResponseException e) {
            return "HTTP " + e.getStatusCode().value();
        } catch (Exception e) {
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }

    private List<Long> robotIds(String baseUrl, String user, String pass, long projectId,
                                String harborRobotName) {
        String url = baseUrl + "/api/v2.0/robots?page_size=100&q=Level%3Dproject,ProjectID%3D" + projectId;
        try {
            String raw = restClient.get().uri(URI.create(url))
                    .header(HttpHeaders.AUTHORIZATION, basic(user, pass))
                    .retrieve().body(String.class);
            JsonNode nodes = JSON.readTree(raw == null ? "[]" : raw);
            List<Long> out = new ArrayList<>();
            for (JsonNode n : nodes) {
                if (harborRobotName.equals(n.path("name").asString(null))) {
                    out.add(n.path("id").asLong());
                }
            }
            return out;
        } catch (RestClientResponseException e) {
            throw failure("查询 Harbor 机器人账号失败", String.valueOf(projectId), e);
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.ERROR.getCode(),
                    "解析 Harbor 机器人账号列表失败：" + e.getMessage(), e);
        }
    }

    private Long projectId(String baseUrl, String user, String pass, String projectName) {
        try {
            String raw = restClient.get().uri(baseUrl + "/api/v2.0/projects/" + projectName)
                    .header(HttpHeaders.AUTHORIZATION, basic(user, pass))
                    .retrieve().body(String.class);
            JsonNode node = JSON.readTree(raw == null ? "{}" : raw);
            long id = node.path("project_id").asLong(0);
            return id == 0 ? null : id;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return null;
            }
            throw failure("查询 Harbor 项目失败", projectName, e);
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.ERROR.getCode(),
                    "解析 Harbor 项目响应失败：" + e.getMessage(), e);
        }
    }

    private static String basic(String user, String pass) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
    }

    private static CloudPlatformException failure(String what, String target,
                                                  RestClientResponseException e) {
        // 不打印请求头（含 Basic）与 body 原文
        return new CloudPlatformException(EnumResponseType.ERROR,
                what + "：" + target + "（HTTP " + e.getStatusCode().value() + "）");
    }
}
