package com.coding.platformapi.metrics;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Prometheus 服务发现只读客户端（按集群 baseUrl 传入，非全局单端点）：
 * <ul>
 *   <li>{@link #scrapePools(String)} → {@code GET /api/v1/scrape_pools} 的 scrapePool 名列表</li>
 *   <li>{@link #targetLabelNames(String, String)} → {@code GET /api/v1/targets?scrapePool=} 活跃 target 的标签名并集</li>
 * </ul>
 * 只负责"拉 + 解析出候选标签名"，不掺业务（选哪个 pool、拼 __name__ 由 ServiceMonitorService 决定）。
 */
@Slf4j
@Component
public class PromDiscoveryClient {

    private final RestClient restClient;
    private final JsonMapper jsonMapper;

    public PromDiscoveryClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
        // lenient mapper：Prometheus 未知属性忽略；envelope/字段用默认命名（status/data/scrapePools/activeTargets）
        this.jsonMapper = JsonMapper.builder()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .build();
    }

    /** scrapePool 名列表（如 {@code serviceMonitor/<ns>/<name>/0}）。 */
    public List<String> scrapePools(String baseUrl) {
        ScrapePoolsResp resp = get(URI.create(baseUrl + "/api/v1/scrape_pools"), ScrapePoolsResp.class);
        if (!"success".equals(resp.status)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "Prometheus 查询失败: " + resp.error);
        }
        return (resp.data != null && resp.data.scrapePools != null) ? resp.data.scrapePools : List.of();
    }

    /** 指定 scrapePool 的活跃 target 标签名并集（去重、保序）：discovered=服务发现原始标签（relabeling 用），relabeled=最终目标标签（metricRelabeling 用）。 */
    public TargetLabelSets labelSets(String baseUrl, String scrapePool) {
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .path("/api/v1/targets")
                .queryParam("scrapePool", scrapePool)
                .build().toUri();
        TargetsResp resp = get(uri, TargetsResp.class);
        if (!"success".equals(resp.status)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "Prometheus 查询失败: " + resp.error);
        }
        Set<String> discovered = new LinkedHashSet<>();
        Set<String> relabeled = new LinkedHashSet<>();
        if (resp.data != null && resp.data.activeTargets != null) {
            for (TargetsResp.Target t : resp.data.activeTargets) {
                if (t.discoveredLabels != null) {
                    discovered.addAll(t.discoveredLabels.keySet());
                }
                if (t.labels != null) {
                    relabeled.addAll(t.labels.keySet());
                }
            }
        }
        return new TargetLabelSets(discovered, relabeled);
    }

    /** 一个 scrapePool 的标签名集合：discovered（服务发现原始）+ relabeled（最终目标标签）。 */
    public record TargetLabelSets(Set<String> discovered, Set<String> relabeled) {
    }

    /**
     * 指定 scrapePool 活跃 target 的 (job, instance) 标识（去重、保序）。
     * 用于拼 MetricRelabeling {@code __name__} 候选的 match[] selector——按本 SM 的真实端点收窄，避免串到别家指标。
     */
    public List<TargetRef> targetRefs(String baseUrl, String scrapePool) {
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .path("/api/v1/targets")
                .queryParam("scrapePool", scrapePool)
                .build().toUri();
        TargetsResp resp = get(uri, TargetsResp.class);
        if (!"success".equals(resp.status)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "Prometheus 查询失败: " + resp.error);
        }
        List<TargetRef> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (resp.data != null && resp.data.activeTargets != null) {
            for (TargetsResp.Target t : resp.data.activeTargets) {
                if (t.labels == null) continue;
                String instance = t.labels.get("instance");
                if (!StringUtils.hasText(instance)) continue; // instance 一定存在；缺失则跳过该 target
                String job = t.labels.get("job");
                TargetRef ref = new TargetRef(job, instance);
                if (seen.add(ref.key())) out.add(ref);
            }
        }
        return out;
    }

    /** 按一组 match[] selector 取 {@code __name__}（指标名）值列表（去重保序）。matchers 为空 → 全局。 */
    public List<String> metricNames(String baseUrl, List<String> matchers) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(baseUrl).path("/api/v1/label/__name__/values");
        if (matchers != null) {
            for (String m : matchers) {
                b.queryParam("match[]", m);
            }
        }
        LabelValuesResp resp = get(b.build().toUri(), LabelValuesResp.class);
        if (!"success".equals(resp.status)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "Prometheus 查询失败: " + resp.error);
        }
        return (resp.data != null) ? resp.data : List.of();
    }

    /** 一个 target 的标识：job + instance（拼精确 match[] 用）。 */
    public record TargetRef(String job, String instance) {
        public String key() {
            return (job == null ? "" : job) + "" + instance;
        }
    }

    /** /api/v1/label/<name>/values 响应（data 为字符串数组） */
    @Data
    public static class LabelValuesResp {
        private String status;
        private String error;
        private List<String> data;
    }

    /** GET + 反序列化 Prometheus 标准 envelope（{status,data,error}）。HTTP/解析异常统一转业务异常。 */
    private <T> T get(URI uri, Class<T> type) {
        String body;
        try {
            body = restClient.get().uri(uri).retrieve().body(String.class);
        } catch (Exception e) {
            log.error("Prometheus discovery 请求失败 {}", uri, e);
            throw new CloudPlatformException(EnumResponseType.ERROR, "查询 Prometheus 失败: " + e.getMessage());
        }
        if (body == null || body.isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "Prometheus 返回空响应");
        }
        try {
            return jsonMapper.readValue(body, type);
        } catch (Exception e) {
            log.error("解析 Prometheus 响应失败：{}", body, e);
            throw new CloudPlatformException(EnumResponseType.ERROR, "Prometheus 响应解析失败");
        }
    }

    /** /api/v1/scrape_pools 响应 */
    @Data
    public static class ScrapePoolsResp {
        private String status;
        private String error;
        private ScrapePoolsData data;

        @Data
        public static class ScrapePoolsData {
            private List<String> scrapePools;
        }
    }

    /** /api/v1/targets 响应（只取 activeTargets[].labels） */
    @Data
    public static class TargetsResp {
        private String status;
        private String error;
        private TargetsData data;

        @Data
        public static class TargetsData {
            private List<Target> activeTargets;
        }

        @Data
        public static class Target {
            /** 服务发现原始标签（__meta_* / __address__ …）→ Relabeling 的 sourceLabels 候选 */
            private Map<String, String> discoveredLabels;
            /** 最终目标标签（relabel 后）→ MetricRelabeling 的 sourceLabels 候选（另加 __name__） */
            private Map<String, String> labels;
        }
    }

}
