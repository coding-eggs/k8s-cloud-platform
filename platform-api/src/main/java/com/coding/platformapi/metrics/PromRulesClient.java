package com.coding.platformapi.metrics;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Prometheus 规则只读客户端：{@code GET <baseUrl>/api/v1/rules}。
 *
 * <p><b>只负责「拉 + 解析」，不掺业务</b> —— 怎么把这些 group 对齐到具体的 PrometheusRule
 * 由 {@code RuleRuntimeMatcher} 决定，降级成「状态未知」由调用方决定。
 * 失败一律抛 {@link CloudPlatformException}（同 {@code PromDiscoveryClient} 的口径），
 * 调用方只 catch 它就能降级，不会漏掉 RuntimeException。
 *
 * <p>⚠️ 与 {@code PromDiscoveryClient} 的一处有意偏离：那个类在自己的构造函数里 {@code new RestClient}，
 * 于是根本测不了。本类收 {@link RestClient.Builder}（生产）或已装配的 {@link RestClient}（测试），
 * 后者同 {@code CredentialProbeClient} 的既有做法。
 */
@Slf4j
@Component
public class PromRulesClient {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    /** 这个响应可能有几百 KB（全套规则），比 discovery 那几个端点重得多。 */
    private static final int READ_TIMEOUT_MS = 30_000;

    private final RestClient restClient;
    private final JsonMapper jsonMapper;

    public PromRulesClient(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        this.restClient = builder.requestFactory(factory).build();
        this.jsonMapper = lenientMapper();
    }

    /** 测试专用：直接接一个已装配的 RestClient（同 {@code CredentialProbeClient} 的口径）。 */
    PromRulesClient(RestClient restClient) {
        this.restClient = restClient;
        this.jsonMapper = lenientMapper();
    }

    /** lenient：Prometheus 未知属性忽略；envelope 字段用默认命名（status/error/data/groups/rules）。 */
    private static JsonMapper lenientMapper() {
        return JsonMapper.builder()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .build();
    }

    /**
     * 拉取并归一。**失败时抛 {@link CloudPlatformException}**，由调用方（{@code PrometheusRuleService}）
     * 降级成「状态未知」—— 客户端只管拉，降级是业务层的事。
     */
    public List<RuleGroup> groups(String baseUrl) {
        RulesResp resp = get(URI.create(baseUrl + "/api/v1/rules"), RulesResp.class);
        if (!"success".equals(resp.status)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "Prometheus 查询失败: " + resp.error);
        }
        if (resp.data == null || resp.data.groups == null) {
            return List.of();
        }
        List<RuleGroup> out = new ArrayList<>(resp.data.groups.size());
        for (RulesResp.GroupJson g : resp.data.groups) {
            List<RuleView> rules = new ArrayList<>();
            if (g.rules != null) {
                for (RulesResp.RuleJson r : g.rules) {
                    // recording 规则没有 state / alerts：两者都留 null，不臆造
                    boolean alerting = "alerting".equals(r.type);
                    rules.add(new RuleView(
                            r.name,
                            r.health,
                            r.lastError,
                            alerting ? r.state : null,
                            alerting && r.alerts != null ? r.alerts.size() : null));
                }
            }
            out.add(new RuleGroup(g.name, g.file, rules));
        }
        return out;
    }

    /** 一个规则组（已归一）。{@code file} 是 prometheus-operator 生成的文件路径。 */
    public record RuleGroup(String name, String file, List<RuleView> rules) {
    }

    /** 一条规则的运行状态（已归一）。{@code activeAlerts} 对 recording 规则恒为 null。 */
    public record RuleView(String name, String health, String lastError, String state, Integer activeAlerts) {
    }

    /** GET + 反序列化 Prometheus 标准 envelope（{status,data,error}）。HTTP/解析异常统一转业务异常。 */
    private <T> T get(URI uri, Class<T> type) {
        String body;
        try {
            body = restClient.get().uri(uri).retrieve().body(String.class);
        } catch (Exception e) {
            log.error("Prometheus rules 请求失败 {}", uri, e);
            throw new CloudPlatformException(EnumResponseType.ERROR, "查询 Prometheus 失败: " + e.getMessage());
        }
        if (body == null || body.isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "Prometheus 返回空响应");
        }
        try {
            return jsonMapper.readValue(body, type);
        } catch (Exception e) {
            log.error("解析 Prometheus rules 响应失败：{}", body, e);
            throw new CloudPlatformException(EnumResponseType.ERROR, "Prometheus 响应解析失败");
        }
    }

    /** /api/v1/rules 响应（只取 data.groups[]）。 */
    @Data
    public static class RulesResp {
        private String status;
        private String error;
        private RulesData data;

        @Data
        public static class RulesData {
            private List<GroupJson> groups;
        }

        @Data
        public static class GroupJson {
            private String name;
            /** prometheus-operator 的文件路径，形如 …/rulefiles-0/<ns>-<CR名>-<uuid>.yaml */
            private String file;
            private List<RuleJson> rules;
        }

        @Data
        public static class RuleJson {
            private String name;
            private String type;            // alerting | recording
            private String health;          // ok | err | unknown
            private String lastError;
            private String state;           // inactive | pending | firing（recording 无）
            private List<Object> alerts;    // 只取长度
        }
    }

}
