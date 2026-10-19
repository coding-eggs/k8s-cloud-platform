package com.coding.platformapi.logs;

import co.elastic.clients.transport.rest5_client.low_level.Request;
import co.elastic.clients.transport.rest5_client.low_level.Response;
import co.elastic.clients.transport.rest5_client.low_level.ResponseException;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 日志查询中心的 ES 客户端：只负责把查询体打到 ES（{@code /<indices>/_search}）并返回原始 JSON。
 *
 * <p>用 Spring Boot 的 {@code spring-boot-starter-data-elasticsearch}：低层 {@link Rest5Client}
 * 由自动配置提供（连接池、超时、{@code spring.elasticsearch.username/password} 的 Basic Auth 都走它），
 * 查询体仍是 {@link LogQueryService} 里拼好的 JSON —— 动态条件（按天索引、租户 OR、search_after）
 * 用 JSON 表达最直观，也便于单测。以后要做聚合/typed 查询，直接注入自动配置的
 * {@code co.elastic.clients.elasticsearch.ElasticsearchClient} 即可，与这里共用同一套连接配置。
 *
 * <p>数据源就是 {@code spring.elasticsearch.uris}（{@code PLATFORM_ES_URL}）；没配时用 starter 的
 * 默认 localhost，查询会在不可达时给出带地址的明确报错。
 */
@Component
public class EsLogClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 发一次 HTTP 的接缝：生产是 Rest5Client，测试直接给 lambda */
    @FunctionalInterface
    interface SearchRunner {
        String run(String path, String body) throws Exception;
    }

    private final SearchRunner runner;
    private final String uris;

    /** ⚠️ 必须标 {@code @Autowired} —— 本类还有第二个（包级私有、给测试注入接缝的）构造器。 */
    @Autowired
    public EsLogClient(Rest5Client client,
                       @Value("${spring.elasticsearch.uris:http://localhost:9200}") String uris) {
        this.uris = uris;
        this.runner = (path, body) -> {
            Request request = new Request("POST", path);
            request.setJsonEntity(body);
            Response response = client.performRequest(request);
            return EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
        };
    }

    EsLogClient(SearchRunner runner, String uris) {
        this.runner = runner;
        this.uris = uris;
    }

    /** 执行一次搜索；indices 是逗号分隔的按天索引列表 */
    public JsonNode search(String indices, Map<String, Object> body) {
        String path = "/" + indices + "/_search?ignore_unavailable=true&allow_no_indices=true";
        String text;
        try {
            text = runner.run(path, JSON.writeValueAsString(body));
        } catch (ResponseException e) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "日志查询失败：ES 返回 HTTP " + e.getResponse().getStatusCode());
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "日志查询不可达（spring.elasticsearch.uris=" + uris + "）：" + e.getMessage());
        }
        try {
            return text == null ? JSON.createObjectNode() : JSON.readTree(text);
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "ES 返回了无法解析的 JSON");
        }
    }
}
