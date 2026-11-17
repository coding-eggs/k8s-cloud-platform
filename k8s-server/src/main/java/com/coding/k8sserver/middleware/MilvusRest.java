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
     * @param credential 形如 {@code Base64("root:口令")}；null / 空 = 不带认证（实例没开鉴权）
     *
     * <p><b>必须是 {@code Basic}</b>：2026-11-17 在真集群（Milvus 2.6.25、开鉴权）实测，
     * {@code Bearer <base64(root:口令)>} 会被拒成 401 {@code {"code":1800,"message":"user hasn't authenticated"}}；
     * {@code Basic <base64(root:口令)>} 或 {@code Bearer root:<明文口令>} 才是 200。
     * 平台手里是 base64 凭据，所以只能走 Basic。
     */
    static JsonNode post(String baseUrl, String path, Map<String, Object> body, String credential) {
        String payload;
        try {
            payload = JSON.writeValueAsString(body == null ? new LinkedHashMap<>() : body);
        } catch (Exception e) {
            throw new IllegalStateException("拼 Milvus 请求体失败", e);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json");
        if (credential != null && !credential.isBlank()) {
            builder.header("Authorization", "Basic " + credential);
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
