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
 * 调 Milvus 的 RESTful v2 改 root 口令。
 *
 * <p><b>为什么必须调 API</b>：Milvus 只在**首次初始化元数据**时用 {@code defaultRootPassword} 建 root
 * （{@code MetaTable.InitCredential} 查到 root 已存在就直接返回）。之后改 CR、改 Secret、重启 Pod
 * 都不会改密码 —— 只能走 {@code update_password}。
 *
 * <p><b>地址</b>用集群内 Service DNS（{@code <实例名>-milvus.<ns>.svc:19530}）：与
 * {@code SeaweedS3Service} 同一前提 —— k8s-server 能连到目标集群的业务网段。平台主集群满足；
 * 跨集群托管而网络不通时，会以连接超时报错（错误信息里带目标地址，便于定位）。
 */
final class MilvusPasswordClient {

    static final String PATH = "/v2/vectordb/users/update_password";

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private MilvusPasswordClient() {
    }

    /** 请求体：Milvus RESTful v2 的字段名（userName / password / newPassword）。 */
    static String body(String username, String oldPassword, String newPassword) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("userName", username);
        m.put("password", oldPassword);
        m.put("newPassword", newPassword);
        try {
            return JSON.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException("拼 Milvus 改密码请求体失败", e);
        }
    }

    /**
     * 改口令。成功 = 2xx 且响应 code（若给出）为 0；否则抛异常，消息里带 Milvus 的原文。
     *
     * @param baseUrl 形如 {@code http://mw-milvus-xxxx-milvus.ns.svc:19530}（不带尾斜杠）
     */
    static void update(String baseUrl, String username, String oldPassword, String newPassword) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + PATH))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        body(username, oldPassword, newPassword), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CloudPlatformException(EnumResponseType.ERROR, "改密码请求被中断");
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "调用 Milvus 接口失败（" + baseUrl + "）：" + e.getMessage());
        }
        if (response.statusCode() / 100 == 2 && codeOf(response.body()) == 0) {
            return;
        }
        throw new CloudPlatformException(EnumResponseType.ERROR,
                "Milvus 拒绝改密码（HTTP " + response.statusCode() + "）：" + brief(response.body()));
    }

    /** Milvus v2 的响应体是 {@code {"code":0,...}}；解析不出就按成功（2xx 已说明问题不大）。 */
    static int codeOf(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return 0;
        }
        try {
            JsonNode code = JSON.readTree(responseBody).get("code");
            return code == null || code.isNull() ? 0 : code.asInt(0);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 错误原文限长：Milvus 有时会把整段提示塞回来，日志/操作记录不需要那么长。 */
    static String brief(String responseBody) {
        if (responseBody == null) {
            return "";
        }
        String oneLine = responseBody.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + "…";
    }
}
