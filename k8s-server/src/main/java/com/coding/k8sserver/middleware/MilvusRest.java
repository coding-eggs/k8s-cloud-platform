package com.coding.k8sserver.middleware;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极小的 Milvus RESTful v2 客户端（只有用户管理这几条端点用）。
 *
 * <p>成功判据与 {@link MilvusPasswordClient} 一致：2xx 且响应里的 {@code code} 为 0。
 */
final class MilvusRest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private MilvusRest() {
    }

    static JsonNode post(String baseUrl, String path, Map<String, Object> body) {
        return post(baseUrl, path, body, null);
    }

    /**
     * @param bearer 形如 {@code Base64("root:口令")}；null = 不带认证（实例没开鉴权）
     */
    static JsonNode post(String baseUrl, String path, Map<String, Object> body, String bearer) {
        String payload;
        try {
            payload = JSON.writeValueAsString(body == null ? new LinkedHashMap<>() : body);
        } catch (Exception e) {
            throw new IllegalStateException("拼 Milvus 请求体失败", e);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json");
        if (bearer != null && !bearer.isBlank()) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        HttpRequest request = builder
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CloudPlatformException(EnumResponseType.ERROR, "Milvus 请求被中断");
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "调用 Milvus 接口失败（" + baseUrl + "）：" + e.getMessage());
        }
        JsonNode node;
        try {
            node = JSON.readTree(response.body() == null || response.body().isBlank()
                    ? "{}" : response.body());
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "Milvus 响应不是 JSON（HTTP " + response.statusCode() + "）：" + brief(response.body()));
        }
        int code = node.path("code").asInt(0);
        if (response.statusCode() / 100 != 2 || code != 0) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "Milvus 拒绝了请求（HTTP " + response.statusCode() + "）：" + brief(response.body()));
        }
        return node;
    }

    static String brief(String body) {
        if (body == null) {
            return "";
        }
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + "…";
    }
}
