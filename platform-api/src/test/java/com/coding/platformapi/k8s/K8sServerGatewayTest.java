package com.coding.platformapi.k8s;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.dto.admin.AdminProbeResult;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 网关的**行为**测试（起一个真 HTTP stub，不打真 k8s-server）：
 * 成功信封 / 空 body / 坏 JSON / 业务 code≠200 四条路径。
 * <p>为什么用 JDK HttpServer 而不是 mock 掉 RestClient：本类要保的正是"真实 HTTP 下的流式行为"，
 * 把 HTTP 层 mock 掉等于把被测对象抽掉。也顺带避免为测试改生产代码的构造器。
 */
class K8sServerGatewayTest {

    private HttpServer server;
    private K8sServerGateway gateway;
    /** 每个用例设成本次响应体（UTF-8） */
    private final AtomicReference<String> body = new AtomicReference<>("");
    private final AtomicReference<Integer> status = new AtomicReference<>(200);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] out = body.get().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status.get(), out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        });
        server.start();
        gateway = new K8sServerGateway("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** 成功信封：{"code":200,"data":{...}} → 返回 data */
    @Test
    void exchange_returns_data_on_success_envelope() {
        body.set("{\"code\":200,\"msg\":\"ok\",\"data\":{\"version\":\"v1.30.0\"}}");

        AdminProbeResult r = gateway.exchange(HttpMethod.POST, "/x", null, null,
                gateway.responseType(AdminProbeResult.class));

        assertThat(r.getVersion()).isEqualTo("v1.30.0");
    }

    /** 空 body（请求没走到业务层，如安全链 401 entry point）→ CloudPlatformException */
    @Test
    void exchange_throws_on_empty_body() {
        body.set("");
        assertThatThrownBy(() -> gateway.exchange(HttpMethod.POST, "/x", null, null,
                gateway.responseType(AdminProbeResult.class)))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("空响应");
    }

    /** 坏 JSON → CloudPlatformException（**且日志里不含全量 body**，见 Task 2.2） */
    @Test
    void exchange_throws_on_malformed_json() {
        body.set("not json at all");
        assertThatThrownBy(() -> gateway.exchange(HttpMethod.POST, "/x", null, null,
                gateway.responseType(AdminProbeResult.class)))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("解析失败");
    }

    /** 信封里 code≠200 → 原样转业务异常（保 msg） */
    @Test
    void exchange_throws_business_error_with_msg() {
        body.set("{\"code\":500,\"msg\":\"集群不可达\"}");
        assertThatThrownBy(() -> gateway.exchange(HttpMethod.POST, "/x", null, null,
                gateway.responseType(AdminProbeResult.class)))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("集群不可达");
    }

    /**
     * 5MB 的坏 body：日志里**不得**出现全量内容（旧实现把整个 body 当参数传给 log.error）。
     * <p>三条断言缺一不可，否则本用例会以两种方式空过：
     * <ul>
     *   <li>{@code isNotEmpty} —— 若实现改成"什么都不记"，只有长度断言时它照样绿；</li>
     *   <li>{@code anySatisfy(含预览)} —— 光有长度上界，"不记预览"也绿，而出错时没有上下文等于没记；</li>
     *   <li>{@code allSatisfy(不含哨兵)} —— 直接判定"整包被打"这件事本身（长度是间接证据）。</li>
     * </ul>
     */
    @Test
    void exchange_does_not_log_the_whole_body_on_parse_failure() {
        String sentinel = "SENTINEL-END-OF-BODY";
        body.set("x".repeat(5 * 1024 * 1024) + sentinel);

        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(K8sServerGateway.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> gateway.exchange(HttpMethod.POST, "/x", null, null,
                    gateway.responseType(AdminProbeResult.class)))
                    .isInstanceOf(CloudPlatformException.class);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .as("解析失败必须留下 ERROR 日志")
                .isNotEmpty();
        assertThat(appender.list)
                .as("日志必须带上前 8KB 预览（body 以 x 开头），否则出错时没有上下文")
                .anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("xxxx"));
        assertThat(appender.list)
                .allSatisfy(e -> {
                    assertThat(e.getFormattedMessage())
                            .as("body 末尾的哨兵串出现在日志里 = 整包被打了")
                            .doesNotContain(sentinel);
                    assertThat(e.getFormattedMessage().length())
                            .as("日志只能带前 8KB 预览，不能带全量 body")
                            .isLessThan(16 * 1024);
                });
    }
}
