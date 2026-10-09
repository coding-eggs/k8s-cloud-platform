package com.coding.platformapi.components;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * exec 中继的上游 URL 构造：**token 必须离开 query**。
 *
 * <p>背景（2026-10-09）：Tomcat WS 客户端构造握手请求时，请求行（含 query）只有 4KB 固定缓冲，
 * header 才自动扩容；admin 的权限闭包 token 已 ~6.4KB，放 query 必 BufferOverflowException。
 * 本测试钉住"上游 URL 里不含 access_token、且其余参数原样保留"。
 */
class PodExecRelayHandlerTest {

    private static final String TOKEN = "eyJhbGciOiJSUzI1NiJ9." + "x".repeat(6400) + ".sig";

    @Test
    void upstream_url_drops_access_token_and_keeps_other_params() {
        URI browser = URI.create("ws://localhost:8081/api/ws/pod/exec"
                + "?access_token=" + TOKEN + "&clusterId=c1&namespace=default&name=web-0&container=app&scope=cluster");
        String upstream = PodExecRelayHandler.buildUpstreamUrl("http://127.0.0.1:8080", browser);

        assertThat(upstream)
                .startsWith("ws://127.0.0.1:8080/ws/pod/exec?clusterId=c1&namespace=default&name=web-0&container=app&scope=cluster")
                .doesNotContain("access_token")
                .doesNotContain(TOKEN);
    }

    @Test
    void upstream_url_tolerates_token_in_any_position_and_empty_query() {
        URI tokenLast = URI.create("ws://h/ws/pod/exec?clusterId=c1&access_token=" + TOKEN);
        assertThat(PodExecRelayHandler.buildUpstreamUrl("http://k8s:8080", tokenLast))
                .isEqualTo("ws://k8s:8080/ws/pod/exec?clusterId=c1");

        URI noQuery = URI.create("ws://h/ws/pod/exec");
        assertThat(PodExecRelayHandler.buildUpstreamUrl("http://k8s:8080", noQuery))
                .isEqualTo("ws://k8s:8080/ws/pod/exec");
    }

    @Test
    void https_base_maps_to_wss() {
        URI browser = URI.create("wss://h/ws/pod/exec?access_token=" + TOKEN + "&namespace=ns");
        assertThat(PodExecRelayHandler.buildUpstreamUrl("https://k8s.internal:8443", browser))
                .isEqualTo("wss://k8s.internal:8443/ws/pod/exec?namespace=ns");
    }

    @Test
    void token_of_reads_value_and_returns_null_when_missing_or_blank() {
        URI uri = URI.create("ws://h/ws/pod/exec?access_token=" + TOKEN + "&namespace=ns");
        assertThat(PodExecRelayHandler.tokenOf(uri)).isEqualTo(TOKEN);

        assertThat(PodExecRelayHandler.tokenOf(URI.create("ws://h/ws/pod/exec?namespace=ns"))).isNull();
        assertThat(PodExecRelayHandler.tokenOf(URI.create("ws://h/ws/pod/exec?access_token="))).isNull();
        assertThat(PodExecRelayHandler.tokenOf(URI.create("ws://h/ws/pod/exec"))).isNull();
        // 相似参数名不得误命中
        assertThat(PodExecRelayHandler.tokenOf(URI.create("ws://h/ws/pod/exec?xaccess_token=abc"))).isNull();
    }
}
