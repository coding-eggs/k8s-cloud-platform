package com.coding.gateway.route;

import com.coding.data.mapper.auth.PlatformPermissionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.core.env.Environment;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 长连接断点清单的配置钉子（计划 Task 7 表格）：改了这些值要先想清楚为什么。 */
@SpringBootTest
class GatewayHttpClientConfigTest {

    @MockitoBean
    PlatformPermissionMapper permissionMapper;

    @Autowired
    HttpClientProperties http;

    @Autowired
    Environment env;

    @Test
    void connectTimeoutSetButNoResponseTimeout() {
        assertThat(http.getConnectTimeout()).isEqualTo(5000);
        // 不设：慢接口、大文件下载由 nginx 的 1800s 兜底
        assertThat(http.getResponseTimeout()).isNull();
    }

    /** 池连接空闲 < 上游 Tomcat keepAliveTimeout(20s)：不复用对端已关的连接 */
    @Test
    void pooledConnectionsRetiredBeforeUpstreamKeepAlive() {
        assertThat(http.getPool().getMaxIdleTime()).isEqualTo(Duration.ofSeconds(15));
        assertThat(http.getPool().getMaxLifeTime()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void websocketFramesUpTo2MB() {
        assertThat(http.getWebsocket().getMaxFramePayloadLength()).isEqualTo(2 * 1024 * 1024);
    }

    @Test
    void gracefulShutdown() {
        assertThat(env.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(env.getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("30s");
    }
}
