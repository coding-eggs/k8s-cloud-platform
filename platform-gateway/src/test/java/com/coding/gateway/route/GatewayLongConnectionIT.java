package com.coding.gateway.route;

import com.coding.common.components.jwt.JwtPermissionResolver;
import com.coding.data.mapper.auth.PlatformPermissionMapper;
import com.coding.data.models.auth.PlatformPermission;
import com.coding.gateway.security.PermissionRegistryReloader;
import com.coding.gateway.support.TestJwt;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.WebsocketClientSpec;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.WebsocketServerSpec;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 长连接经网关不断、不缓冲（计划 Task 7 断点清单）。{@code @Tag("slow")} 的两条默认不跑，
 * 用 {@code -DexcludeSlow=} 打开。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestJwt.LocalKeys.class)
class GatewayLongConnectionIT {

    private static final int MB = 1024 * 1024;
    private static final AtomicReference<String> WS_URI_SEEN = new AtomicReference<>();

    /** api 上游：回显 WS + 慢速分块下载 */
    static final DisposableServer API = HttpServer.create().host("127.0.0.1").port(0)
            .route(r -> r
                    .get("/api/ws/pod/exec", (req, res) -> {
                        WS_URI_SEEN.set(req.uri());
                        return res.sendWebsocket((in, out) -> out.sendString(in.aggregateFrames(4 * MB).receive().asString()),
                                WebsocketServerSpec.builder().maxFramePayloadLength(4 * MB).build());
                    })
                    .get("/api/download", (req, res) -> res.header("Content-Type", "application/octet-stream")
                            .send(Flux.interval(Duration.ZERO, Duration.ofMillis(200)).take(10)
                                    .map(i -> Unpooled.wrappedBuffer(new byte[MB])))))
            .bindNow();

    /** auth 上游：仿 Tomcat，空闲连接 20s 后由服务端关闭 */
    static final DisposableServer AUTH = HttpServer.create().host("127.0.0.1").port(0)
            .idleTimeout(Duration.ofSeconds(20))
            .route(r -> r.get("/auth/internal/clients/list", (req, res) -> res.sendString(Mono.just("ok"))))
            .bindNow();

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry r) {
        r.add("PLATFORM_API_URL", () -> "http://127.0.0.1:" + API.port());
        r.add("PLATFORM_AUTH_URL", () -> "http://127.0.0.1:" + AUTH.port());
    }

    @AfterAll
    static void stop() {
        API.disposeNow();
        AUTH.disposeNow();
    }

    @MockitoBean
    PlatformPermissionMapper permissionMapper;

    @MockitoBean
    JwtPermissionResolver permissionResolver;

    @Autowired
    PermissionRegistryReloader reloader;

    @LocalServerPort
    int port;

    @BeforeEach
    void rows() {
        when(permissionMapper.selectAllActive()).thenReturn(List.of(
                row("platform-api", "/download"), row("platform-auth", "/internal/clients/list")));
        when(permissionResolver.resolve(anyString(), any())).thenReturn(List.of("t:view"));
        reloader.reload();
    }

    private static PlatformPermission row(String service, String path) {
        PlatformPermission p = new PlatformPermission();
        p.setDomain("API");
        p.setService(service);
        p.setAction("GET");
        p.setResource(path);
        p.setCode("t:view");
        return p;
    }

    private ReactorNettyWebSocketClient wsClient() {
        return new ReactorNettyWebSocketClient(HttpClient.create(),
                () -> WebsocketClientSpec.builder().maxFramePayloadLength(4 * MB));
    }

    private URI execUri() {
        return URI.create("ws://127.0.0.1:" + port + "/api/ws/pod/exec?access_token=" + TestJwt.token() + "&ns=a");
    }

    /** 收齐 expectedLength 个字符为止（大消息可能被拆成多帧） */
    private static Mono<String> receiveText(org.springframework.web.reactive.socket.WebSocketSession s, int expectedLength) {
        return s.receive().map(WebSocketMessage::getPayloadAsText)
                .scan("", String::concat)
                .filter(t -> t.length() >= expectedLength)
                .next();
    }

    @Test
    void wsUpgradeWithQueryToken() {
        AtomicReference<String> echo = new AtomicReference<>();

        wsClient().execute(execUri(), s -> s.send(Mono.just(s.textMessage("hello")))
                        .then(receiveText(s, 5).doOnNext(echo::set)).then())
                .block(Duration.ofSeconds(10));

        assertThat(echo.get()).isEqualTo("hello");
        assertThat(WS_URI_SEEN.get()).contains("access_token=").contains("ns=a");
    }

    @Test
    void wsLargeFrame() {
        String big = "x".repeat(256 * 1024);
        AtomicReference<String> echo = new AtomicReference<>();

        wsClient().execute(execUri(), s -> s.send(Mono.just(s.textMessage(big)))
                        .then(receiveText(s, big.length()).doOnNext(echo::set)).then())
                .block(Duration.ofSeconds(10));

        // 不直接 isEqualTo：失败时会把 256KB 打进报告
        assertThat(big.equals(echo.get())).as("256KB 帧原样回显（实际长度 %s）",
                echo.get() == null ? null : echo.get().length()).isTrue();
    }

    @Test
    @Tag("slow")
    void wsSurvivesIdle() {
        List<String> replies = new CopyOnWriteArrayList<>();

        wsClient().execute(execUri(), s -> s.send(Flux.concat(Mono.just("a"),
                                Mono.delay(Duration.ofSeconds(70)).thenReturn("b")).map(s::textMessage))
                        .and(s.receive().map(WebSocketMessage::getPayloadAsText).take(2).doOnNext(replies::add)))
                .block(Duration.ofSeconds(90));

        assertThat(replies).containsExactly("a", "b");
    }

    @Test
    void downloadIsStreamed() {
        long start = System.nanoTime();
        AtomicLong firstChunkMs = new AtomicLong(-1);

        Integer total = WebClient.create("http://127.0.0.1:" + port).get().uri("/api/download")
                .header("Authorization", "Bearer " + TestJwt.token())
                .retrieve().bodyToFlux(DataBuffer.class)
                .map(b -> {
                    firstChunkMs.compareAndSet(-1, (System.nanoTime() - start) / 1_000_000);
                    int n = b.readableByteCount();
                    DataBufferUtils.release(b);
                    return n;
                })
                .reduce(0, Integer::sum)
                .block(Duration.ofSeconds(20));
        long totalMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(total).isEqualTo(10 * MB);
        // 上游写完要 ~1.8s；首块不到 1s 就到 = 网关边收边发，没有攒整包
        assertThat(firstChunkMs.get()).isLessThan(1000);
        assertThat(totalMs).isGreaterThan(firstChunkMs.get() + 1000);
    }

    @Test
    @Tag("slow")
    void pooledConnectionReuseAfterUpstreamIdleClose() throws InterruptedException {
        WebClient c = WebClient.create("http://127.0.0.1:" + port);
        String auth = "Bearer " + TestJwt.token();

        assertThat(c.get().uri("/api/oauth-clients/list").header("Authorization", auth)
                .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5))).isEqualTo("ok");
        Thread.sleep(21_000);
        assertThat(c.get().uri("/api/oauth-clients/list").header("Authorization", auth)
                .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5))).isEqualTo("ok");
    }
}
