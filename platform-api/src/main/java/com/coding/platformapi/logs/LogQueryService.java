package com.coding.platformapi.logs;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.platformapi.components.OtelObjects;
import com.coding.platformapi.services.ClusterService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志查询中心：把 UI 的筛选条件翻译成 ES 查询。
 *
 * <p>数据来自 collector 写进 ES 的按天索引（{@code otel-logs-YYYY.MM.DD}）；文档是
 * {@code mapping mode = none} 的**扁平点号 key** 形态：
 * <pre>
 *   @timestamp, SeverityText, SeverityNumber, Body, TraceId, SpanId, TraceNumber,
 *   Resource.service.name / Resource.cluster.name / Resource.k8s.namespace.name /
 *   Resource.k8s.pod.name / Resource.k8s.deployment.name ...,
 *   Attributes.*
 * </pre>
 *
 * <p>租户隔离：请求里带租户时按 {@code platform_tenant_namespace} 的分配行收窄成
 * 「集群名 + 命名空间」的 OR 条件；租户用户由 controller 强制注入自己的租户，不能越权。
 */
@Service
public class LogQueryService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter INDEX_DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    /** 时间范围上限：按天索引，超过一个月等于一次扫几十个索引 */
    private static final int MAX_RANGE_DAYS = 31;
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final EsLogClient es;
    private final PlatformTenantNamespaceMapper allocations;
    private final ClusterService clusters;

    public LogQueryService(EsLogClient es, PlatformTenantNamespaceMapper allocations,
                           ClusterService clusters) {
        this.es = es;
        this.allocations = allocations;
        this.clusters = clusters;
    }

    /**
     * @param forcedTenantId 非空 = 强制只看这个租户（租户用户调用时由 controller 注入）
     */
    public List<LogEntryDTO> search(LogQueryRequest req, String forcedTenantId) {
        long end = req.getEndMillis() != null ? req.getEndMillis() : System.currentTimeMillis();
        long start = req.getStartMillis() != null ? req.getStartMillis() : end - 3_600_000L;
        if (start >= end) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                    "时间范围不合法（开始必须早于结束）");
        }
        if (ChronoUnit.DAYS.between(
                Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate(),
                Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate()) > MAX_RANGE_DAYS) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                    "时间范围最多 " + MAX_RANGE_DAYS + " 天");
        }
        int limit = req.getLimit() == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, req.getLimit()));

        List<Object> filter = new ArrayList<>();
        filter.add(Map.of("range", Map.of("@timestamp", Map.of(
                "gte", Instant.ofEpochMilli(start).toString(),
                "lte", Instant.ofEpochMilli(end).toString()))));
        if (StringUtils.hasText(req.getClusterName())) {
            filter.add(term("Resource.cluster.name.keyword", req.getClusterName().trim()));
        }
        if (StringUtils.hasText(req.getNamespace())) {
            filter.add(term("Resource.k8s.namespace.name.keyword", req.getNamespace().trim()));
        }
        if (StringUtils.hasText(req.getSeverity())) {
            filter.add(Map.of("terms", Map.of("SeverityText.keyword",
                    List.of(req.getSeverity().trim().toUpperCase()))));
        }
        if (StringUtils.hasText(req.getWorkload())) {
            filter.add(workloadFilter(req.getWorkload().trim()));
        }
        String tenant = StringUtils.hasText(forcedTenantId) ? forcedTenantId : req.getTenantId();
        if (StringUtils.hasText(tenant)) {
            filter.add(tenantFilter(tenant.trim()));
        }

        List<Object> must = new ArrayList<>();
        if (StringUtils.hasText(req.getKeyword())) {
            must.add(Map.of("simple_query_string", Map.of(
                    "query", req.getKeyword().trim(),
                    "fields", List.of("Body", "Attributes.*"),
                    "default_operator", "and")));
        }

        Map<String, Object> bool = new LinkedHashMap<>();
        if (!must.isEmpty()) {
            bool.put("must", must);
        }
        bool.put("filter", filter);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", limit);
        body.put("track_total_hits", false);
        body.put("sort", List.of(
                Map.of("@timestamp", Map.of("order", "desc", "unmapped_type", "date")),
                Map.of("_doc", "desc")));
        if (req.getSearchAfter() != null && !req.getSearchAfter().isEmpty()) {
            body.put("search_after", req.getSearchAfter());
        }
        body.put("query", Map.of("bool", bool));

        JsonNode response = es.search(String.join(",", indices(start, end)), body);
        return toRows(response.path("hits").path("hits"));
    }

    /** 按天索引，UTC 日期（collector 的 logstash_format 用进程时区，容器里是 UTC），两侧各扩一天 */
    List<String> indices(long startMillis, long endMillis) {
        LocalDate from = Instant.ofEpochMilli(startMillis).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1);
        LocalDate to = Instant.ofEpochMilli(endMillis).atZone(ZoneOffset.UTC).toLocalDate().plusDays(1);
        List<String> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            out.add(OtelObjects.LOG_INDEX_PREFIX + "-" + d.format(INDEX_DATE));
        }
        return out;
    }

    private static Map<String, Object> term(String field, String value) {
        return Map.of("term", Map.of(field, value));
    }

    /** 工作负载名：模糊匹配 service.name 与三种工作负载 / Pod 名（用户可能只记得一半） */
    private static Map<String, Object> workloadFilter(String workload) {
        String pattern = "*" + workload.toLowerCase() + "*";
        Map<String, Object> wildcard = Map.of("value", pattern, "case_insensitive", true);
        List<Object> should = List.of(
                Map.of("wildcard", Map.of("Resource.service.name.keyword", wildcard)),
                Map.of("wildcard", Map.of("Resource.k8s.deployment.name.keyword", wildcard)),
                Map.of("wildcard", Map.of("Resource.k8s.statefulset.name.keyword", wildcard)),
                Map.of("wildcard", Map.of("Resource.k8s.daemonset.name.keyword", wildcard)),
                Map.of("wildcard", Map.of("Resource.k8s.pod.name.keyword", wildcard)));
        return Map.of("bool", Map.of("should", should, "minimum_should_match", 1));
    }

    /** 租户 → 分配表里的 (集群, 命名空间) 对，OR 成一棵 bool */
    private Map<String, Object> tenantFilter(String tenantId) {
        List<PlatformTenantNamespace> rows = allocations.listByTenant(tenantId);
        if (rows.isEmpty()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "该租户没有分配任何命名空间，无法查询日志");
        }
        List<Object> should = new ArrayList<>();
        for (PlatformTenantNamespace row : rows) {
            String clusterName;
            try {
                clusterName = clusters.requireName(row.getClusterId());
            } catch (Exception e) {
                continue; // 集群已删除：分配行还在，跳过
            }
            should.add(Map.of("bool", Map.of("filter", List.of(
                    term("Resource.cluster.name.keyword", clusterName),
                    term("Resource.k8s.namespace.name.keyword", row.getNamespace())))));
        }
        if (should.isEmpty()) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "该租户的集群都不存在，无法查询日志");
        }
        return Map.of("bool", Map.of("should", should, "minimum_should_match", 1));
    }

    @SuppressWarnings("unchecked")
    private static List<LogEntryDTO> toRows(JsonNode hits) {
        List<LogEntryDTO> out = new ArrayList<>();
        for (JsonNode hit : hits) {
            JsonNode src = hit.path("_source");
            LogEntryDTO e = new LogEntryDTO();
            e.setTime(text(src.path("@timestamp")));
            e.setSeverity(text(src.path("SeverityText")));
            e.setSeverityNumber(src.path("SeverityNumber").isNumber()
                    ? src.path("SeverityNumber").asInt() : null);
            e.setBody(bodyText(src.path("Body")));
            e.setTraceId(text(src.path("TraceId")));
            e.setSpanId(text(src.path("SpanId")));
            e.setService(text(src.path("Resource.service.name")));
            e.setCluster(text(src.path("Resource.cluster.name")));
            e.setNamespace(text(src.path("Resource.k8s.namespace.name")));
            e.setPod(text(src.path("Resource.k8s.pod.name")));
            e.setWorkload(firstNonBlank(
                    text(src.path("Resource.k8s.deployment.name")),
                    text(src.path("Resource.k8s.statefulset.name")),
                    text(src.path("Resource.k8s.daemonset.name"))));

            Map<String, String> attrs = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> entry : src.properties()) {
                String name = entry.getKey();
                if (name.startsWith("Attributes.")) {
                    attrs.put(name.substring("Attributes.".length()), text(entry.getValue()));
                }
            }
            e.setAttributes(attrs.isEmpty() ? null : attrs);
            JsonNode sortNode = hit.path("sort");
            if (sortNode.isArray()) {
                List<Object> sort = new ArrayList<>();
                for (JsonNode v : sortNode) {
                    sort.add(v.isNumber() ? (Object) v.asLong() : v.isString() ? (Object) v.asString() : v.toString());
                }
                e.setSort(sort);
            }
            out.add(e);
        }
        return out;
    }

    private static String bodyText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.isString() ? node.asString() : node.toString();
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asString(null);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }
}
