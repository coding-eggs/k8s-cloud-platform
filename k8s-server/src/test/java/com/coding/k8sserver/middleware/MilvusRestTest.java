package com.coding.k8sserver.middleware;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milvus RESTful v2 的认证头。
 *
 * <p>2026-11-17 在真集群（Milvus 2.6.25、开启鉴权）逐个试出来的结果：
 * <pre>
 *   Authorization: Bearer &lt;base64(root:口令)&gt;  → 401 {"code":1800,"message":"user hasn't authenticated"}
 *   Authorization: Basic  &lt;base64(root:口令)&gt;  → 200 {"code":0,...}
 *   Authorization: Bearer root:&lt;明文口令&gt;        → 200
 *   （不带 Authorization）                        → 401
 * </pre>
 * 平台手里是 base64 的 {@code root:口令}，所以必须发 <b>Basic</b>。
 */
class MilvusRestTest {

    /** 起一个本地 HTTP 服务把请求头录下来，验的是真正发出去的那一行。 */
    @Test
    void sendsBasicWithTheBase64Credential() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        HttpServer server = server(seen);
        try {
            MilvusRest.post(base(server), "/v2/vectordb/users/list", Map.of(), "cm9vdDpwdw==");
            assertThat(seen.get()).isEqualTo("Basic cm9vdDpwdw==");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void omitsAuthorizationWhenCredentialIsBlank() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        HttpServer server = server(seen);
        try {
            MilvusRest.post(base(server), "/v2/vectordb/users/list", Map.of(), null);
            assertThat(seen.get()).isNull();

            seen.set("sentinel");
            MilvusRest.post(base(server), "/v2/vectordb/users/list", Map.of(), "  ");
            assertThat(seen.get()).isNull();
        } finally {
            server.stop(0);
        }
    }

    private static String base(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static HttpServer server(AtomicReference<String> seen) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/vectordb/users/list", exchange -> {
            seen.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"code\":0,\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }
}
