package com.coding.k8sserver.middleware;

import com.coding.common.exception.CloudPlatformException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 改口令的 HTTP 客户端：请求形状 / 成功判据 / 失败信息。
 *
 * <p>启动一个只回预设响应的本地 HttpServer 来断言真实发出的请求，比 mock HttpClient 更接近事实
 * （路径、Content-Type、JSON 字段名写错都能被发现）。
 */
class MilvusPasswordClientTest {

    @Test
    void updatePostsTheThreeFieldsToTheRestfulEndpoint() {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        HttpServer server = server("/v2/vectordb/users/update_password", 200, "{\"code\":0,\"data\":{}}",
                path, body, contentType);
        try {
            MilvusPasswordClient.update(base(server), "root", "old-pw", "new-pw");

            assertThat(path.get()).isEqualTo("/v2/vectordb/users/update_password");
            assertThat(contentType.get()).startsWith("application/json");
            assertThat(body.get())
                    .contains("\"userName\":\"root\"")
                    .contains("\"password\":\"old-pw\"")
                    .contains("\"newPassword\":\"new-pw\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aNonZeroMilvusCodeIsReportedWithItsMessage() {
        // Milvus 的错误码放在 body 里；HTTP 可能是 200，不能只看状态码
        HttpServer server = server("/v2/vectordb/users/update_password", 200,
                "{\"code\":1100,\"message\":\"invalid password\"}", new AtomicReference<>(),
                new AtomicReference<>(), new AtomicReference<>());
        try {
            assertThatThrownBy(() -> MilvusPasswordClient.update(base(server), "root", "wrong", "new"))
                    .isInstanceOf(CloudPlatformException.class)
                    .hasMessageContaining("invalid password")
                    .hasMessageContaining("HTTP 200");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aNon2xxStatusIsAnErrorEvenWithCodeZeroBody() {
        HttpServer server = server("/v2/vectordb/users/update_password", 401,
                "{\"code\":0}", new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>());
        try {
            assertThatThrownBy(() -> MilvusPasswordClient.update(base(server), "root", "old", "new"))
                    .hasMessageContaining("HTTP 401");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anUnreachableEndpointBecomesAReadableError() {
        assertThatThrownBy(() -> MilvusPasswordClient.update("http://127.0.0.1:1", "root", "old", "new"))
                .hasMessageContaining("调用 Milvus 接口失败")
                .hasMessageContaining("127.0.0.1:1");
    }

    @Test
    void responseParsingToleratesMissingOrBrokenBodies() {
        assertThat(MilvusPasswordClient.codeOf(null)).isZero();
        assertThat(MilvusPasswordClient.codeOf("")).isZero();
        assertThat(MilvusPasswordClient.codeOf("not json")).isZero();
        assertThat(MilvusPasswordClient.codeOf("{\"code\":65535}")).isEqualTo(65535);
        assertThat(MilvusPasswordClient.brief("a\n b   c")).isEqualTo("a b c");
        assertThat(MilvusPasswordClient.brief("x".repeat(400))).hasSize(301).endsWith("…");
    }

    // ==================== 夹具 ====================

    private static String base(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static HttpServer server(String context, int status, String response,
                                     AtomicReference<String> path, AtomicReference<String> body,
                                     AtomicReference<String> contentType) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(context, exchange -> {
                path.set(exchange.getRequestURI().getPath());
                contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
