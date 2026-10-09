package com.coding.platformapi.components;

import com.coding.common.components.jwt.QueryParameterBearerTokenResolver;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pod exec WS 中继：前端 ⇄ platform-api ⇄ k8s-server /ws/pod/exec。
 * <p>
 * 浏览器侧握手只能走 query（浏览器 WS 不能自定义请求头），token 取 query access_token，经既有 JWT 安全链。
 * 建立后其余 query（clusterId/namespace/name/container/tenantId/scope…）原样透传给上游，
 * **但 access_token 不跟着去** —— 见下。
 *
 * <p><b>为什么上游那一跳必须把 token 挪到 Authorization 头</b>（2026-10-09 修）：
 * Tomcat WS 客户端构造握手请求时，请求行（含 query）用的是 **4KB 固定缓冲**，而 header 走
 * {@code putWithExpand} 会自动扩容。admin 的权限闭包（data.permissions 装全部 code）已让 token 长到
 * ~6.4KB，把它塞进 query 必然 {@code BufferOverflowException}（{@code WsWebSocketContainer.createRequest}
 * 的 query 那一行），exec 直接连不上。挪到 header 后该跳不再有 4KB 上限，且
 * k8s-server 的 {@code QueryParameterBearerTokenResolver} 本就 header 优先。
 * 附带收益：token 不再出现在 k8s-server 及任何网关的 access log 里（URL 会被记，header 不会）。
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
        String token = tokenOf(session.getUri());
        if (token == null) {
            //⚠️ 不打 session.getUri()：浏览器那次握手的 URL 里就带着 access_token，打日志即泄露
            log.warn("exec 中继拒绝：缺少 access_token（session={}）", session.getId());
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        String upstreamUrl = upstreamUrl(session.getUri());
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        wsClient.execute(new RelayUpstreamHandler(session), headers, URI.create(upstreamUrl))
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

    /**上游地址：k8s.server.url(http→ws) + /ws/pod/exec + 浏览器原始 query **去掉 access_token**
     *（token 走 Authorization 头，理由见类注释）。其余参数保持原样，交给 k8s-server 解析。 */
    private String upstreamUrl(URI browserUri) {
        return buildUpstreamUrl(k8sServerUrl, browserUri);
    }

    /**纯函数形态：便于单测（不依赖 Spring 注入）。 */
    static String buildUpstreamUrl(String k8sServerUrl, URI browserUri) {
        String base = k8sServerUrl.startsWith("https://")
                ? "wss://" + k8sServerUrl.substring(8)
                : "ws://" + k8sServerUrl.replaceFirst("^http://", "");
        String rest = queryWithoutToken(browserUri == null ? null : browserUri.getRawQuery());
        return rest.isEmpty() ? base + "/ws/pod/exec" : base + "/ws/pod/exec?" + rest;
    }

    /**浏览器握手 URL 里的 access_token 值；JWT 字符集本不会被百分号编码，这里仍解码一次作防御。 */
    static String tokenOf(URI uri) {
        String rawQuery = uri == null ? null : uri.getRawQuery();
        if (rawQuery == null) return null;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && QueryParameterBearerTokenResolver.QUERY_PARAM.equals(pair.substring(0, eq))) {
                String v = pair.substring(eq + 1);
                return v.isEmpty() ? null : URLDecoder.decode(v, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /**剔除 access_token 这一对，其余原样拼回（不重新编码，避免动到 k8s-server 侧已按 raw 解析的值）。 */
    static String queryWithoutToken(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && QueryParameterBearerTokenResolver.QUERY_PARAM.equals(pair.substring(0, eq))) continue;
            if (pair.isEmpty()) continue;
            if (sb.length() > 0) sb.append('&');
            sb.append(pair);
        }
        return sb.toString();
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
