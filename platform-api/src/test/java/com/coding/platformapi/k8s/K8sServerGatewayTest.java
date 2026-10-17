package com.coding.platformapi.k8s;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 后台任务（虚拟线程）调用 k8s-server 的凭据传递。
 *
 * <p>真实故障：组件安装在虚拟线程里执行，那里没有请求上下文 → 不带 Authorization →
 * k8s-server 401 → 操作记录 FAILED。修法是让调用方把 Authorization 显式带进 worker。
 */
class K8sServerGatewayTest {

    private HttpServer server;
    private K8sServerGateway gateway;
    private final AtomicReference<String> received = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ping", exchange -> {
            received.set(exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION));
            byte[] body = "{\"code\":200,\"msg\":\"ok\",\"data\":null}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        gateway = new K8sServerGateway("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void ping() {
        gateway.exchange(HttpMethod.GET, "/ping", null, null, gateway.responseType(Object.class));
    }

    @Test
    void aDelegatedAuthorizationIsUsedWhenThereIsNoRequestContext() {
        K8sServerGateway.runAs("Bearer test-token", this::ping);

        assertThat(received.get()).isEqualTo("Bearer test-token");
    }

    @Test
    void withoutDelegationNoAuthorizationHeaderIsSent() {
        ping();

        assertThat(received.get()).isNull();
    }

    @Test
    void theDelegationDoesNotLeakPastItsScope() {
        K8sServerGateway.runAs("Bearer test-token", () -> { });

        assertThat(K8sServerGateway.delegatedAuthorization()).isNull();
    }

    @Test
    void theDelegationIsClearedEvenWhenTheBodyThrows() {
        assertThatThrownBy(() -> K8sServerGateway.runAs("Bearer test-token", () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(K8sServerGateway.delegatedAuthorization()).isNull();
    }
}
