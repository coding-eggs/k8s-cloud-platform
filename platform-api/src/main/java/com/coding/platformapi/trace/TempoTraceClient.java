package com.coding.platformapi.trace;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Tempo 的 HTTP 查询（平台自研 trace 查看的数据源）。
 *
 * <p>平台-api 跑在管理集群里，直接查 Tempo 的 {@code :3200}；业务集群的 trace 已由各自的 collector
 * 回传到这里，所以查询**只打管理集群这一份**。
 *
 * <p>Tempo 的 trace-by-ID 在不同版本里把 batch 字段叫 {@code batches}（Tempo 2.x）或
 * {@code resourceSpans}（OTLP 兼容），解析两种都接受。
 */
@Component
public class TempoTraceClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * 单条 TraceQL 的字符预算。Tempo 3.1 的解析器在 query 约 2KB 时开始出现
     * {@code parse error … unexpected $end}（2026-10-30 用 21 个命名空间的租户复现），
     * 这里留足余量；超出就把租户范围切批查，再在平台侧合并。
     */
    private static final int MAX_SCOPE_QUERY_CHARS = 1400;

    /** 一条范围条件里最多塞多少个命名空间（控制单段正则的长度） */
    private static final int MAX_NAMESPACES_PER_CLAUSE = 24;

    private final RestClient restClient;
    private final String baseUrl;

    /** ⚠️ 必须标 {@code @Autowired} —— 本类还有第二个（包级私有、给测试注入 RestClient 的）构造器。 */
    @Autowired
    public TempoTraceClient(
            @Value("${platform.tempo.query-url:http://platform-tempo.monitor.svc:3200}") String baseUrl) {
        this(defaultRestClient(), baseUrl);
    }

    /** 测试用：注入绑定了 MockRestServiceServer 的 RestClient */
    TempoTraceClient(RestClient restClient, String baseUrl) {
        this.restClient = restClient;
        this.baseUrl = trimSlash(baseUrl);
    }

    private static RestClient defaultRestClient() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(30000);
        return RestClient.builder().requestFactory(factory).build();
    }

    /** TraceQL 搜索；时间范围缺省最近 1 小时，limit 1..200。 */
    public List<TraceSummaryDTO> search(TraceSearchRequest req) {
        return search(req, List.of());
    }

    /**
     * @param scopes 租户可见范围（{@code empty} = 平台管理员，不加条件）；非空时按集群压成
     *               {@code cluster && namespace =~ "a|b|c"} 交给 Tempo 服务端过滤。
     *
     *               <p>⚠️ 不能展开成 {@code (cluster && namespace="a") || (cluster && namespace="b") ...}：
     *               21 个命名空间的租户会拼出 2KB+ 的 TraceQL，Tempo 3.1 的解析器在这种长度上会
     *               直接回 {@code 400 … parse error … unexpected $end}（2026-10-30 实报，压成
     *               590 字符的正则写法就正常）。范围再大时按 {@link #MAX_SCOPE_QUERY_CHARS}
     *               切批，多次查询在平台侧合并。
     */
    public List<TraceSummaryDTO> search(TraceSearchRequest req, List<TraceScope> scopes) {
        TraceSearchRequest r = req == null ? new TraceSearchRequest() : req;
        long end = r.getEnd() != null ? r.getEnd() : System.currentTimeMillis() / 1000;
        long start = r.getStart() != null ? r.getStart() : end - 3600;
        if (start >= end) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                    "trace 查询的时间范围不合法（start 必须早于 end）");
        }
        int limit = r.getLimit() == null ? 20 : Math.max(1, Math.min(200, r.getLimit()));

        List<String> queries = traceQlVariants(r.getClusterName(), r.getNamespace(), r.getServiceName(),
                r.getErrorsOnly(), r.getMinDurationMs(), scopes);
        List<TraceSummaryDTO> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String q : queries) {
            URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                    .path("/api/search")
                    .queryParam("q", q)
                    .queryParam("start", start)
                    .queryParam("end", end)
                    .queryParam("limit", limit)
                    .build().encode().toUri();
            JsonNode body = getJson(uri);
            for (JsonNode t : body.path("traces")) {
                String traceId = text(t.path("traceID"));
                if (traceId != null && !seen.add(traceId)) {
                    continue; // 切批后同一条 trace 可能被两批都命中
                }
                out.add(new TraceSummaryDTO(
                        traceId,
                        text(t.path("rootServiceName")),
                        text(t.path("rootTraceName")),
                        text(t.path("startTimeUnixNano")),
                        durationMs(t),
                        serviceCount(t),
                        spanCount(t)));
            }
        }
        if (queries.size() > 1) {
            // 多批结果要自己按时间倒序再截断（单批保持 Tempo 返回的顺序）
            out.sort(Comparator.comparing((TraceSummaryDTO d) ->
                    d.getStartTimeUnixNano() == null ? "" : d.getStartTimeUnixNano()).reversed());
            if (out.size() > limit) {
                out = new ArrayList<>(out.subList(0, limit));
            }
        }
        return out;
    }

    /**
     * 耗时：优先用顶层 {@code durationMs}；部分 Tempo 版本的 /api/search 不返回它 ——
     * 退回到根 span 的 {@code durationNanos}（根 span 的耗时就是整条 trace 的耗时）。
     */
    private static Long durationMs(JsonNode trace) {
        if (trace.path("durationMs").isNumber()) {
            return trace.path("durationMs").asLong();
        }
        JsonNode spans = trace.path("spanSet").path("spans");
        if (spans.size() == 0) {
            spans = trace.path("spanSets").path(0).path("spans");
        }
        if (spans.size() > 0) {
            long nanos = parseLong(text(spans.get(0).path("durationNanos")));
            return nanos > 0 ? nanos / 1_000_000 : null;
        }
        return null;
    }

    /** 涉及几个服务（serviceStats 的条目数） */
    private static int serviceCount(JsonNode trace) {
        JsonNode stats = trace.path("serviceStats");
        return stats.isObject() ? stats.size() : 0;
    }

    /** span 总数：各服务 spanCount 之和 */
    private static int spanCount(JsonNode trace) {
        int total = 0;
        for (JsonNode stat : trace.path("serviceStats")) {
            JsonNode count = stat.path("spanCount");
            if (count.isNumber()) {
                total += count.asInt();
            }
        }
        return total;
    }

    /** 某集群下出现过的 service.name 列表（Tempo v2 标签值；页面下拉候选，失败由调用方展示） */
    public List<String> serviceNames(String clusterName, Long start, Long end) {
        return serviceNames(clusterName, null, start, end, List.of());
    }

    /** 同上，叠加租户可见范围（空 = 平台管理员） */
    public List<String> serviceNames(String clusterName, Long start, Long end,
                                     List<TraceScope> scopes) {
        return serviceNames(clusterName, null, start, end, scopes);
    }

    /** 同上，可再按命名空间收窄（顶栏/页面筛选） */
    public List<String> serviceNames(String clusterName, String namespace, Long start, Long end,
                                     List<TraceScope> scopes) {
        long to = end != null ? end : System.currentTimeMillis() / 1000;
        long from = start != null ? start : to - 3600;
        if (from >= to) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                    "trace 查询的时间范围不合法（start 必须早于 end）");
        }
        // 租户范围可能被切批（见 search 的注释）：候选值取并集
        Set<String> values = new TreeSet<>();
        for (String q : traceQlVariants(clusterName, namespace, null, null, null, scopes)) {
            URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                    .path("/api/v2/search/tag/resource.service.name/values")
                    .queryParam("q", q)
                    .queryParam("start", from)
                    .queryParam("end", to)
                    .build().encode().toUri();
            JsonNode body = getJson(uri);
            for (JsonNode item : body.path("tagValues")) {
                String value = text(item.path("value"));
                if (value != null && !value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return new ArrayList<>(values);
    }

    /** 按 traceId 取完整调用链，拍平成按开始时间排序的 span 列表。 */
    public TraceDetailDTO get(String traceId) {
        if (traceId == null || !traceId.matches("[0-9a-fA-F]{16,32}")) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                    "非法的 traceId：" + traceId);
        }
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .path("/api/traces/{traceId}")
                .buildAndExpand(traceId).encode().toUri();

        // 404 对 trace-by-id 有特定含义：该 trace 没有被导出（采样率 < 100% 时日志里的
        // 未采样 traceId 就会出现），或已超出 Tempo 保留期 —— 与「Tempo 不可达」区分开。
        JsonNode body = getJson(uri, "trace 不存在：该请求未被链路采样，或已超出 Tempo 保留期");
        JsonNode batches = body.path("batches");
        if (!batches.isArray()) {
            batches = body.path("resourceSpans");
        }

        List<SpanDTO> spans = new ArrayList<>();
        for (JsonNode batch : batches) {
            Map<String, String> resource = attributes(batch.path("resource").path("attributes"));
            String service = resource.get("service.name");
            String cluster = resource.get("cluster.name");
            String namespace = resource.get("k8s.namespace.name");
            JsonNode scopes = batch.path("scopeSpans");
            if (!scopes.isArray()) {
                scopes = batch.path("scope_spans");
            }
            for (JsonNode scope : scopes) {
                for (JsonNode s : scope.path("spans")) {
                    spans.add(span(s, service, cluster, namespace));
                }
            }
        }
        spans.sort(Comparator.comparingLong(s -> parseLong(s.getStartTimeUnixNano())));
        return new TraceDetailDTO(traceId, spans);
    }

    private JsonNode getJson(URI uri) {
        return getJson(uri, null);
    }

    /**
     * @param notFoundMessage 404 时用的提示；{@code null} = 统一按「Tempo 查询失败」报
     */
    private JsonNode getJson(URI uri, String notFoundMessage) {
        String text;
        try {
            text = restClient.get()
                    .uri(uri)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 404 && notFoundMessage != null) {
                throw new CloudPlatformException(EnumResponseType.ERROR, notFoundMessage);
            }
            // 把 Tempo 的响应体带出来：400 多半是 TraceQL 解析错误，正文里就是出错位置
            // （只报「HTTP 400」时排障得自己去集群里捞日志，2026-10-30 踩过）
            String detail = e.getResponseBodyAsString();
            String suffix = detail == null || detail.isBlank()
                    ? "" : "（" + ellipsis(detail.replaceAll("\\s+", " ").trim(), 300) + "）";
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "Tempo 查询失败：HTTP " + status + suffix);
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "Tempo 不可达：" + e.getMessage());
        }
        try {
            return text == null ? JSON.createObjectNode() : JSON.readTree(text);
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "Tempo 返回了无法解析的 JSON");
        }
    }

    private static SpanDTO span(JsonNode s, String service, String cluster, String namespace) {
        long start = parseLong(textOrNumber(s.path("startTimeUnixNano")));
        long end = parseLong(textOrNumber(s.path("endTimeUnixNano")));
        return new SpanDTO(
                text(s.path("spanId")),
                text(s.path("parentSpanId")),
                text(s.path("name")),
                service, cluster, namespace,
                String.valueOf(start), String.valueOf(end),
                end >= start ? (end - start) / 1_000_000 : 0L,
                text(s.path("status").path("code")),
                attributes(s.path("attributes")));
    }

    private static Map<String, String> attributes(JsonNode array) {
        Map<String, String> out = new LinkedHashMap<>();
        if (array == null || !array.isArray()) {
            return out;
        }
        for (JsonNode a : array) {
            String key = text(a.path("key"));
            if (key != null) {
                out.put(key, otlpValue(a.path("value")));
            }
        }
        return out;
    }

    private static String otlpValue(JsonNode v) {
        if (v == null) {
            return "";
        }
        for (String field : List.of("stringValue", "intValue", "boolValue", "doubleValue")) {
            String value = text(v.path(field));
            if (value != null) {
                return value;
            }
        }
        JsonNode array = v.path("arrayValue").path("values");
        if (array.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode item : array) {
                parts.add(otlpValue(item));
            }
            return String.join(",", parts);
        }
        return v.toString();
    }

    /**
     * 租户范围作为**一个**条件加入（按集群压成 {@code namespace =~ "a|b|c"}），与集群/命名空间/服务/
     * 错误/耗时条件之间仍是 AND —— 租户即使用别人的集群名筛，也只会得到空结果。
     *
     * <p>范围大到超过 {@link #MAX_SCOPE_QUERY_CHARS} 时返回多个 query（调用方逐批查询再合并）：
     * Tempo 3.1 的 TraceQL 解析器在 2KB 左右会 400（见 {@code search} 的注释）。
     */
    private static List<String> traceQlVariants(String cluster, String namespace, String service,
                                                Boolean errorsOnly, Integer minDurationMs,
                                                List<TraceScope> scopes) {
        List<String> conditions = new ArrayList<>();
        if (StringUtils.hasText(cluster)) {
            conditions.add("resource.cluster.name = \"" + escape(cluster) + "\"");
        }
        if (StringUtils.hasText(namespace)) {
            conditions.add("resource.k8s.namespace.name = \"" + escape(namespace) + "\"");
        }
        if (StringUtils.hasText(service)) {
            conditions.add("resource.service.name = \"" + escape(service) + "\"");
        }
        if (Boolean.TRUE.equals(errorsOnly)) {
            conditions.add("status = error");
        }
        if (minDurationMs != null && minDurationMs > 0) {
            conditions.add("duration > " + minDurationMs + "ms");
        }
        String base = conditions.isEmpty() ? "" : String.join(" && ", conditions);
        List<String> clauses = scopeClauses(scopes);
        if (clauses.isEmpty()) {
            return List.of(base.isEmpty() ? "{}" : "{ " + base + " }");
        }
        // 按字符预算把范围条件分批，每批都是「基础条件 && (范围1 || 范围2 ...)」
        List<String> out = new ArrayList<>();
        List<String> batch = new ArrayList<>();
        int budget = MAX_SCOPE_QUERY_CHARS - base.length();
        for (String clause : clauses) {
            int added = clause.length() + (batch.isEmpty() ? 0 : 4);
            if (!batch.isEmpty() && added > budget) {
                out.add(join(base, batch));
                batch = new ArrayList<>();
                budget = MAX_SCOPE_QUERY_CHARS - base.length();
                added = clause.length();
            }
            batch.add(clause);
            budget -= added;
        }
        if (!batch.isEmpty()) {
            out.add(join(base, batch));
        }
        return out;
    }

    private static String join(String base, List<String> clauses) {
        String scope = "(" + String.join(" || ", clauses) + ")";
        return "{ " + (base.isEmpty() ? scope : base + " && " + scope) + " }";
    }

    /**
     * 租户范围 → TraceQL 片段。**同一集群的命名空间合成一条正则**（TraceQL 的 {@code =~} 是全匹配，
     * 不存在子串误命中的问题），避免每个命名空间都写一对 {@code (cluster && namespace)}。
     * 单个集群的命名空间再多也按 {@link #MAX_NAMESPACES_PER_CLAUSE} 再切，控制单段长度。
     */
    private static List<String> scopeClauses(List<TraceScope> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return List.of();
        }
        Map<String, List<String>> byCluster = new LinkedHashMap<>();
        for (TraceScope scope : scopes) {
            byCluster.computeIfAbsent(scope.clusterName(), k -> new ArrayList<>()).add(scope.namespace());
        }
        List<String> clauses = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : byCluster.entrySet()) {
            List<String> names = entry.getValue();
            for (int i = 0; i < names.size(); i += MAX_NAMESPACES_PER_CLAUSE) {
                List<String> slice = names.subList(i, Math.min(i + MAX_NAMESPACES_PER_CLAUSE, names.size()));
                StringBuilder regex = new StringBuilder();
                for (String name : slice) {
                    if (regex.length() > 0) {
                        regex.append('|');
                    }
                    regex.append(regexEscape(name));
                }
                clauses.add("(resource.cluster.name = \"" + escape(entry.getKey())
                        + "\" && resource.k8s.namespace.name =~ \"" + regex + "\")");
            }
        }
        return clauses;
    }

    /** 命名空间是 DNS 标签，本不需要转义；这里仍按正则元字符转义，避免将来放宽命名规则时出错 */
    private static String regexEscape(String value) {
        return value.replaceAll("([\\\\.^$|?*+()\\[\\]{}])", "\\\\$1");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String ellipsis(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asString(null);
    }

    /** 时间戳在 OTLP JSON 里通常是字符串，但某些版本会输出数字 —— 两种都接受 */
    private static String textOrNumber(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asString(null);
        return value != null ? value : node.toString();
    }

    private static String trimSlash(String value) {
        String v = value == null ? "" : value.trim();
        return v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
    }
}
