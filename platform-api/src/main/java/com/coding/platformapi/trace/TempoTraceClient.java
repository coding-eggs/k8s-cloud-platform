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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        TraceSearchRequest r = req == null ? new TraceSearchRequest() : req;
        long end = r.getEnd() != null ? r.getEnd() : System.currentTimeMillis() / 1000;
        long start = r.getStart() != null ? r.getStart() : end - 3600;
        if (start >= end) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                    "trace 查询的时间范围不合法（start 必须早于 end）");
        }
        int limit = r.getLimit() == null ? 20 : Math.max(1, Math.min(200, r.getLimit()));

        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .path("/api/search")
                .queryParam("q", traceQl(r.getClusterName(), r.getServiceName()))
                .queryParam("start", start)
                .queryParam("end", end)
                .queryParam("limit", limit)
                .build().encode().toUri();

        JsonNode body = getJson(uri);
        List<TraceSummaryDTO> out = new ArrayList<>();
        for (JsonNode t : body.path("traces")) {
            out.add(new TraceSummaryDTO(
                    text(t.path("traceID")),
                    text(t.path("rootServiceName")),
                    text(t.path("rootTraceName")),
                    text(t.path("startTimeUnixNano")),
                    t.path("durationMs").isNumber() ? t.path("durationMs").asLong() : null));
        }
        return out;
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

        JsonNode body = getJson(uri);
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
        String text;
        try {
            text = restClient.get().uri(uri).accept(MediaType.APPLICATION_JSON)
                    .retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "Tempo 查询失败：HTTP " + e.getStatusCode().value());
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

    private static String traceQl(String cluster, String service) {
        List<String> conditions = new ArrayList<>();
        if (StringUtils.hasText(cluster)) {
            conditions.add("resource.cluster.name = \"" + escape(cluster) + "\"");
        }
        if (StringUtils.hasText(service)) {
            conditions.add("resource.service.name = \"" + escape(service) + "\"");
        }
        return conditions.isEmpty() ? "{}" : "{ " + String.join(" && ", conditions) + " }";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
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
