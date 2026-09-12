package com.coding.platformapi.metrics;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.platformapi.metrics.model.PromApiResponse;
import com.coding.platformapi.metrics.model.PromQueryData;
import com.coding.platformapi.metrics.model.PromResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thanos Query 通用收发：只收**完整 promql** + 时间参数 + 反序列化目标类，返回 {@code List<PromResult<T>>}。
 * <p>全局单端点、无鉴权；两个方法都自动带 {@code dedup=true&partial_response=true}。
 * 不感知枚举、不感知业务——具体拼哪条查询由调用方（MetricsService）决定。
 */
@Slf4j
@Component
public class ThanosQueryClient {

    private final String baseUrl;
    private final RestClient restClient;
    private final JsonMapper jsonMapper;

    public ThanosQueryClient(@Value("${thanos.query.url}") String baseUrl) {
        this.baseUrl = baseUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        // 跨集群区间查询，读超时放宽
        factory.setReadTimeout(60_000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
        // 专用 lenient mapper：envelope/标签用默认命名（resultType/errorType 为 camelCase），不套全局 snake_case
        this.jsonMapper = JsonMapper.builder()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .build();
    }

    /** 即时查询 → vector。time 可空（空 = now） */
    public <T> List<PromResult<T>> query(String sql, String time, Class<T> labels) {
        Map<String, String> p = common();
        if (time != null && !time.isBlank()) {
            p.put("time", time);
        }
        return unwrap(post("/api/v1/query", p, sql, labels));
    }

    /** 区间查询 → matrix */
    public <T> List<PromResult<T>> queryRange(String sql, long start, long end, int step, Class<T> labels) {
        Map<String, String> p = common();
        p.put("start", String.valueOf(start));
        p.put("end", String.valueOf(end));
        p.put("step", String.valueOf(step));
        return unwrap(post("/api/v1/query_range", p, sql, labels));
    }

    /** 两个方法共用的通用参数 */
    private Map<String, String> common() {
        var m = new LinkedHashMap<String, String>();
        m.put("dedup", "true");
        m.put("partial_response", "true");
        return m;
    }

    private <T> List<PromResult<T>> unwrap(PromQueryData<PromResult<T>> data) {
        return data.getResult() != null ? data.getResult() : List.of();
    }

    @SuppressWarnings("unchecked")
    private <T> PromQueryData<PromResult<T>> post(String path, Map<String, String> params, String sql, Class<T> labels) {
        if (sql == null || sql.isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "查询语句为空（该 MetricQuery 模板尚未填写）");
        }
        // POST：参数走 form body（application/x-www-form-urlencoded），不再拼进 URL query string
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        params.forEach(form::add);
        form.add("query", sql);
        log.info("Metrics PromSQL: {}", sql);

        var tf = jsonMapper.getTypeFactory();
        JavaType type = tf.constructParametricType(PromApiResponse.class,
                tf.constructParametricType(PromQueryData.class,
                        tf.constructParametricType(PromResult.class, labels)));

        StatusBody sb;
        try {
            sb = restClient.post()
                    .uri(baseUrl + path)
                    .body(form)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .exchange((req, res) -> new StatusBody(res.getStatusCode().value(), res.bodyTo(String.class)));
        } catch (Exception e) {
            log.error("Thanos Query {} 请求失败", path, e);
            throw new CloudPlatformException(EnumResponseType.ERROR, "查询 Thanos 失败: " + e.getMessage());
        }
        if (sb.body() == null || sb.body().isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "Thanos 返回空响应 (HTTP " + sb.status() + ")");
        }

        PromApiResponse<PromQueryData<PromResult<T>>> resp;
        try {
            resp = jsonMapper.readValue(sb.body(), type);
        } catch (Exception e) {
            log.error("解析 Thanos 响应失败：{}", sb.body(), e);
            throw new CloudPlatformException(EnumResponseType.ERROR, "Thanos 响应解析失败");
        }
        if (resp == null || !"success".equals(resp.getStatus()) || resp.getData() == null) {
            String err = resp != null && resp.getError() != null ? resp.getError()
                    : (resp != null ? resp.getErrorType() : "HTTP " + sb.status());
            throw new CloudPlatformException(EnumResponseType.ERROR, "Thanos 查询失败: " + err);
        }
        return resp.getData();
    }

    /**
     * 拼**绝对 URI**（baseUrl + path，不带 query）。POST 参数全走 form body，
     * 故 promql 里的 {@code {...}} 不会进 URL、也不会被当 URI 模板变量解析。
     */
    private URI buildUri(String path) {
        return UriComponentsBuilder.fromUriString(baseUrl).path(path).build().toUri();
    }

    /** HTTP 状态码 + 原始响应体 */
    private record StatusBody(int status, String body) {
    }
}
