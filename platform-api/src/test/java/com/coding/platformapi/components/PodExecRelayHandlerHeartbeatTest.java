package com.coding.platformapi.components;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 终端心跳（网关计划 Task 8）：浏览器每 25s 发 {@code {"type":"ping"}}，中继直接回 pong、<b>不转发</b>给 k8s-server。
 * 其它帧原样转发；判 type 的轻量解析失败也按普通帧转发，绝不因此断开。
 */
class PodExecRelayHandlerHeartbeatTest {

    private final PodExecRelayHandler handler = new PodExecRelayHandler(JsonMapper.builder().build());
    private final WebSocketSession browser = mock(WebSocketSession.class);
    private final WebSocketSession upstream = mock(WebSocketSession.class);

    @BeforeEach
    void wire() {
        when(browser.getId()).thenReturn("b1");
        when(browser.isOpen()).thenReturn(true);
        when(upstream.isOpen()).thenReturn(true);
        handler.registerUpstream("b1", upstream);
    }

    @Test
    void pingIsAnsweredNotForwarded() throws Exception {
        handler.handleTextMessage(browser, new TextMessage("{\"type\":\"ping\"}"));

        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(browser).sendMessage(sent.capture());
        assertThat(sent.getValue().getPayload()).isEqualTo("{\"type\":\"pong\"}");
        verify(upstream, never()).sendMessage(any());
    }

    @Test
    void inputIsForwarded() throws Exception {
        String input = "{\"type\":\"input\",\"data\":\"ls\\n\"}";

        handler.handleTextMessage(browser, new TextMessage(input));

        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(upstream).sendMessage(sent.capture());
        assertThat(sent.getValue().getPayload()).isEqualTo(input);
    }

    @Test
    void nonJsonIsForwarded() throws Exception {
        handler.handleTextMessage(browser, new TextMessage("abc"));

        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(upstream).sendMessage(sent.capture());
        assertThat(sent.getValue().getPayload()).isEqualTo("abc");
        verify(browser, never()).close(any(CloseStatus.class));
    }

    /** 上游还没连上时 ping 也要回：前端靠 pong 判活，握手慢不该被当成断线 */
    @Test
    void pingAnsweredEvenBeforeUpstreamReady() throws Exception {
        WebSocketSession early = mock(WebSocketSession.class);
        when(early.getId()).thenReturn("b2");
        when(early.isOpen()).thenReturn(true);

        handler.handleTextMessage(early, new TextMessage("{\"type\":\"ping\"}"));

        verify(early).sendMessage(any(TextMessage.class));
    }
}
