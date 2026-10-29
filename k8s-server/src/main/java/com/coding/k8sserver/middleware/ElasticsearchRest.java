package com.coding.k8sserver.middleware;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.middleware.EsIndexDetailDTO;
import com.coding.common.models.middleware.EsIndexSummaryDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;

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
 * 极小的 Elasticsearch REST 客户端（只读：索引列表 / 索引结构 / 未分配分片）。
 *
 * <h3>这里是**唯一**一条 ES 的 HTTP 通道</h3>
 * 用户管理（{@link ElasticsearchUserAdapter}）也走 {@link #call}，不再各自拼一套。
 * 一套连接逻辑里躺着三个容易分叉的判断（scheme、CA、凭据），复制一份就是复制两份错的机会：
 * 真集群上已经踩过「关掉 TLS 后 ECK 照样生成证书 Secret，拿 Secret 当判据就会用 https 连明文端口」。
 *
 * <h3>连哪儿</h3>
 * ECK 一定建 {@code <名字>-es-http} 这个 Service（用户就是照它写连接串的），协议跟着 CR 的
 * {@code spec.http.tls} 走；https 时只信实例自己的 CA（{@code -es-http-certs-public} 的 {@code ca.crt}），
 * **不** trust-all。
 *
 * <h3>为什么用 HTTP 而不是 exec 进 Pod</h3>
 * 状态判定每轮都要读（列表页每次刷新一次），exec 要起一次 SPDY 会话，比一次内网 HTTP 贵得多；
 * 而且租户侧的 k8s RBAC 未必给 {@code pods/exec}。
 */
final class ElasticsearchRest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 浏览类请求：mapping 大的索引解析得慢，给宽一点 */
    private static final Duration BROWSE_TIMEOUT = Duration.ofSeconds(10);
    /** 状态归因：夹在列表刷新里，必须短 —— 宁可这条消息退化成「red」也不能拖慢整个列表 */
    private static final Duration STATUS_TIMEOUT = Duration.ofSeconds(3);

    private static final String CAT_INDICES = "/_cat/indices?format=json"
            + "&h=index,health,status,pri,rep,docs.count,store.size,creation.date.string";
    private static final String CAT_SHARDS = "/_cat/shards?format=json"
            + "&h=index,shard,prirep,state,unassigned.reason";

    /** 降级消息里最多点几个索引名；再多就只说数量（消息列放不下） */
    private static final int MAX_NAMED_INDICES = 3;

    private ElasticsearchRest() {
    }

    // ================================================================== 入口

    /** 索引列表（按名字排序）。 */
    static List<EsIndexSummaryDTO> listIndices(KubernetesClient client, String namespace, String name) {
        return indicesFrom(get(client, namespace, name, null, CAT_INDICES, BROWSE_TIMEOUT));
    }

    /** 一个索引的 mapping / settings / aliases。 */
    static EsIndexDetailDTO indexDetail(KubernetesClient client, String namespace, String name,
                                        String index) {
        JsonNode body = get(client, namespace, name, null, "/" + encodeSegment(index), BROWSE_TIMEOUT);
        return detailFrom(index, body);
    }

    /**
     * 集群健康是 red 时，说明**到底是哪些主分片坏了**。
     * <p>返回 null = 没有未分配的主分片，调用方退回通用文案。
     *
     * @param es 调用方手里已有的 ES CR（状态判定本来就拿到了），避免再读一次
     */
    static String redSummary(KubernetesClient client, String namespace, String name,
                             GenericKubernetesResource es) {
        return redSummary(get(client, namespace, name, es, CAT_SHARDS, STATUS_TIMEOUT));
    }

    // ================================================================== 传输

    /**
     * 发一次 ES 请求，返回原始响应体。
     *
     * @param es   ES CR；null = 自己读一次（索引接口就是这样，读完正好用来判 scheme）
     * @param body 非 GET/DELETE 时的 JSON 请求体
     */
    static String call(KubernetesClient client, String namespace, String instanceName,
                       GenericKubernetesResource es, String method, String path,
                       Object body, Duration timeout) {
        GenericKubernetesResource cr = es != null ? es
                : client.genericKubernetesResources(ElasticsearchMiddlewareAdapter.CRD)
                        .inNamespace(namespace).withName(instanceName).get();
        if (cr == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "读不到 Elasticsearch 实例的 CR：" + namespace + "/" + instanceName);
        }
        String password = password(client, namespace, instanceName);
        boolean https = ElasticsearchMiddlewareAdapter.tlsEnabled(specOf(cr));
        String caPem = https ? caPemOf(client, namespace, instanceName) : null;
        String base = baseUrl(https, namespace, instanceName);

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(timeout)
                .header("Authorization", basic(ElasticsearchUserAdapter.BUILTIN, password))
                .header("Content-Type", "application/json");
        try {
            HttpRequest request = switch (method) {
                case "GET" -> builder.GET().build();
                case "DELETE" -> builder.DELETE().build();
                default -> builder.method(method, HttpRequest.BodyPublishers.ofString(
                        body == null ? "" : JSON.writeValueAsString(body), StandardCharsets.UTF_8)).build();
            };
            HttpResponse<String> response = httpClient(https, caPem, timeout).send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "Elasticsearch 拒绝了请求（HTTP " + response.statusCode() + "）："
                                + snippet(response.body()));
            }
            return response.body();
        } catch (CloudPlatformException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CloudPlatformException(EnumResponseType.ERROR, "Elasticsearch 请求被中断");
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "调用 Elasticsearch 接口失败（" + base + "）：" + e.getMessage());
        }
    }

    private static JsonNode get(KubernetesClient client, String namespace, String name,
                                GenericKubernetesResource es, String path, Duration timeout) {
        String body = call(client, namespace, name, es, "GET", path, null, timeout);
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "Elasticsearch 响应无法解析");
        }
    }

    /** ECK 生成的口令：Secret {@code <名字>-es-elastic-user} 的 {@code elastic} 键。 */
    static String password(KubernetesClient client, String namespace, String name) {
        String secretName = name + ElasticsearchUserAdapter.ELASTIC_SECRET_SUFFIX;
        Secret secret = client.secrets().inNamespace(namespace).withName(secretName).get();
        if (secret == null || secret.getData() == null
                || secret.getData().get(ElasticsearchUserAdapter.BUILTIN) == null) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "找不到 Elasticsearch 的超管凭据 Secret：" + secretName);
        }
        return new String(Base64.getDecoder()
                .decode(secret.getData().get(ElasticsearchUserAdapter.BUILTIN)), StandardCharsets.UTF_8);
    }

    /**
     * 实例自己的 CA（PEM 的 Base64）。
     * <p>两个 Secret 都读出来交给 {@link ElasticsearchUserAdapter#caPem}：它会区分
     * 「确实没开 TLS」与「开了 TLS 但 CA 缺失」，后者直接报错而不是静默退回 http。
     */
    private static String caPemOf(KubernetesClient client, String namespace, String name) {
        Secret publicCerts = client.secrets().inNamespace(namespace)
                .withName(name + ElasticsearchUserAdapter.CA_SECRET_SUFFIX).get();
        Secret caKeyPair = client.secrets().inNamespace(namespace)
                .withName(name + ElasticsearchUserAdapter.CA_KEYPAIR_SUFFIX).get();
        return ElasticsearchUserAdapter.caPem(publicCerts, caKeyPair);
    }

    private static HttpClient httpClient(boolean https, String caPem, Duration timeout) throws Exception {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(timeout);
        if (https) {
            builder.sslContext(ElasticsearchUserAdapter.sslContext(caPem));
        }
        return builder.build();
    }

    // ================================================================== 纯解析（单测覆盖）

    /** {@code <名字>-es-http.<ns>.svc:9200}，协议跟着 CR 的 http.tls 走。 */
    static String baseUrl(boolean tls, String namespace, String name) {
        return (tls ? "https://" : "http://") + name + ElasticsearchUserAdapter.SERVICE_SUFFIX
                + "." + namespace + ".svc:9200";
    }

    /** `_cat/indices` 的行 → DTO。`_cat` 一律给字符串，缺失是 null 而不是 0。 */
    static List<EsIndexSummaryDTO> indicesFrom(JsonNode cat) {
        List<EsIndexSummaryDTO> out = new ArrayList<>();
        if (cat == null || !cat.isArray()) {
            return out;
        }
        for (JsonNode row : cat) {
            EsIndexSummaryDTO dto = new EsIndexSummaryDTO();
            dto.setName(text(row, "index"));
            dto.setHealth(text(row, "health"));
            dto.setStatus(text(row, "status"));
            dto.setPrimaries(integer(row, "pri"));
            dto.setReplicas(integer(row, "rep"));
            dto.setDocs(longValue(row, "docs.count"));
            dto.setStoreSize(blankToNull(text(row, "store.size")));
            dto.setCreatedAt(blankToNull(text(row, "creation.date.string")));
            out.add(dto);
        }
        out.sort((a, b) -> String.valueOf(a.getName()).compareTo(String.valueOf(b.getName())));
        return out;
    }

    /**
     * {@code GET /<index>} → 三段结构。
     * <p>索引不存在时 ES 回 404（由调用方报错），这里只处理「有响应但没这段」的情形。
     */
    static EsIndexDetailDTO detailFrom(String index, JsonNode body) {
        JsonNode one = body == null ? null : body.get(index);
        EsIndexDetailDTO dto = new EsIndexDetailDTO();
        dto.setName(index);
        dto.setMapping(pretty(one == null ? null : one.get("mappings")));
        dto.setSettings(pretty(one == null ? null : one.get("settings")));
        // 别名连同各自的 filter 原样带出：那才是「这个别名看得见哪些文档」
        dto.setAliases(pretty(one == null ? null : one.get("aliases")));
        return dto;
    }

    /**
     * `_cat/shards` → 「N 个主分片未分配：名字（原因）…」。
     * <p>只数 {@code prirep=p} 且 {@code state=UNASSIGNED} 的行：副本没分配只是少一份冗余（yellow），
     * 主分片没分配才是数据不可读（red），两者不能混在一起说。
     */
    static String redSummary(JsonNode shards) {
        if (shards == null || !shards.isArray()) {
            return null;
        }
        // 索引 → 原因（一个索引多个主分片也只点名一次）
        Map<String, String> byIndex = new LinkedHashMap<>();
        for (JsonNode row : shards) {
            if (!"p".equals(text(row, "prirep")) || !"UNASSIGNED".equals(text(row, "state"))) {
                continue;
            }
            String index = text(row, "index");
            if (index == null || index.isBlank()) {
                continue;
            }
            String reason = blankToNull(text(row, "unassigned.reason"));
            byIndex.putIfAbsent(index, reason == null ? "原因未知" : reason);
        }
        if (byIndex.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder().append(byIndex.size()).append(" 个主分片未分配：");
        int named = 0;
        for (Map.Entry<String, String> e : byIndex.entrySet()) {
            if (named == MAX_NAMED_INDICES) {
                out.append(" 等 ").append(byIndex.size()).append(" 个索引");
                break;
            }
            if (named > 0) {
                out.append("、");
            }
            out.append(e.getKey()).append("（").append(e.getValue()).append("）");
            named++;
        }
        return out.toString();
    }

    /** 索引名进路径：`+` 这类字符不编码会变成空格，`/` 会改变路径。 */
    static String encodeSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // ================================================================== 内部

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static String snippet(String body) {
        String oneLine = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + "…";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> specOf(GenericKubernetesResource es) {
        Object spec = es.getAdditionalProperties() == null
                ? null : es.getAdditionalProperties().get("spec");
        return spec instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String pretty(JsonNode node) {
        if (node == null || node.isNull()) {
            return "{}";
        }
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String text(JsonNode row, String field) {
        JsonNode v = row.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Integer integer(JsonNode row, String field) {
        String v = text(row, field);
        if (v == null || v.isBlank() || "-".equals(v)) {
            return null;
        }
        try {
            return Integer.valueOf(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long longValue(JsonNode row, String field) {
        String v = text(row, field);
        if (v == null || v.isBlank() || "-".equals(v)) {
            return null;
        }
        try {
            return Long.valueOf(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() || "-".equals(v) ? null : v;
    }
}
