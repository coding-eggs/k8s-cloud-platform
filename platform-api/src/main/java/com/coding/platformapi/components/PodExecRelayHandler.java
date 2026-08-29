package com.coding.platformapi.components;

import com.coding.common.components.jwt.QueryParameterBearerTokenResolver;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pod exec WS 中继：前端 ⇄ platform-api ⇄ k8s-server /ws/pod/exec。
 * <p>
 * 握手经既有 JWT 安全链（token 走 query access_token，admin authority）；
 * 建立后把浏览器 query 原样透传给上游（含 access_token），k8s-server 侧再做
 * 双模身份解析 + 分配表边界校验。文本帧双向透传，任一侧关闭则联动关闭另一侧。
 */
@Slf4j
@Component
public class PodExecRelayHandler extends AbstractWebSocketHandler {

    private final StandardWebSocketClient wsClient = new StandardWebSocketClient();

    private final JsonMapper jsonMapper;

    /**浏览器 sessionId → 上游 k8s-server session */
    private final Map<String, WebSocketSession> upstreams = new ConcurrentHashMap<>();

    @Value("${k8s.server.url:http://127.0.0.1:8080}")
    private String k8sServerUrl;

    public PodExecRelayHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String upstreamUrl = upstreamUrl(session.getUri());
        if (upstreamUrl == null) {
            log.warn("exec 中继拒绝：缺少 access_token {}", session.getUri());
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        wsClient.execute(new RelayUpstreamHandler(session), upstreamUrl)
                .whenComplete((upstream, err) -> {
                    if (err != null || upstream == null) {
                        log.error("exec 中继连接 k8s-server 失败: {}", upstreamUrl, err);
                        sendJson(session, Map.of("type", "error", "message", "exec 通道建立失败"));
                        closeQuietly(session, CloseStatus.SERVER_ERROR);
                        return;
                    }
                    upstreams.put(session.getId(), upstream);
                    log.debug("exec 中继已建立: {} → {}", session.getId(), upstreamUrl);
                });
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        WebSocketSession upstream = upstreams.get(session.getId());
        if (upstream == null || !upstream.isOpen()) {
            return;
        }
        try {
            synchronized (upstream) {
                upstream.sendMessage(new TextMessage(message.getPayload()));
            }
        } catch (IOException e) {
            log.debug("exec 中继上行失败: {}", e.getMessage());
            closeQuietly(session, CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        closeUpstream(session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.debug("exec 中继浏览器侧传输错误: {}", exception.getMessage());
        closeUpstream(session.getId(), CloseStatus.SERVER_ERROR);
        closeQuietly(session, CloseStatus.SERVER_ERROR);
    }

    @PreDestroy
    public void shutdown() {
        upstreams.keySet().forEach(id -> closeUpstream(id, CloseStatus.GOING_AWAY));
    }

    /**上游 handler：消息转发回浏览器，关闭/错误联动 */
    private final class RelayUpstreamHandler extends AbstractWebSocketHandler {
        private final WebSocketSession browser;

        private RelayUpstreamHandler(WebSocketSession browser) {
            this.browser = browser;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            if (!browser.isOpen()) {
                return;
            }
            try {
                synchronized (browser) {
                    browser.sendMessage(new TextMessage(message.getPayload()));
                }
            } catch (IOException e) {
                log.debug("exec 中继下行失败: {}", e.getMessage());
                closeQuietly(browser, CloseStatus.SERVER_ERROR);
            }
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            upstreams.remove(browser.getId(), session);
            closeQuietly(browser, status != null ? status : CloseStatus.NORMAL);
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            log.debug("exec 中继上游传输错误: {}", exception.getMessage());
            upstreams.remove(browser.getId(), session);
            closeQuietly(browser, CloseStatus.SERVER_ERROR);
        }
    }

    private void closeUpstream(String browserSessionId, CloseStatus status) {
        WebSocketSession upstream = upstreams.remove(browserSessionId);
        if (upstream != null) {
            closeQuietly(upstream, status);
        }
    }

    /**上游地址：k8s.server.url(http→ws) + /ws/pod/exec + 浏览器原始 query（含 access_token） */
    private String upstreamUrl(URI browserUri) {
        String rawQuery = browserUri.getRawQuery();
        if (rawQuery == null || !containsParam(rawQuery, QueryParameterBearerTokenResolver.QUERY_PARAM)) {
            return null;
        }
        String base = k8sServerUrl.startsWith("https://")
                ? "wss://" + k8sServerUrl.substring(8)
                : "ws://" + k8sServerUrl.replaceFirst("^http://", "");
        return base + "/ws/pod/exec?" + rawQuery;
    }

    private boolean containsParam(String rawQuery, String key) {
        for (String pair : rawQuery.split("&")) {
            if (pair.startsWith(key + "=")) {
                return StringUtils.hasText(pair.substring(key.length() + 1));
            }
        }
        return false;
    }

    private void sendJson(WebSocketSession session, Map<String, Object> payload) {
        try {
            byte[] bytes = jsonMapper.writeValueAsBytes(payload);
            synchronized (session) {
                session.sendMessage(new TextMessage(new String(bytes, StandardCharsets.UTF_8)));
            }
        } catch (Exception e) {
            log.debug("exec 中继错误帧发送失败: {}", e.getMessage());
        }
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (IOException ignored) {
        }
    }

}
