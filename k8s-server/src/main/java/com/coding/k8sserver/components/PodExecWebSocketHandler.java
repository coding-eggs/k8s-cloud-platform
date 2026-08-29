package com.coding.k8sserver.components;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.k8score.factory.KubernetesClientFactory;
import com.coding.k8sserver.components.ResourceAccessResolver.AccessContext;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pod exec 终端 WebSocket（/ws/pod/exec）。
 * <p>
 * 握手经既有 JWT 安全链（authenticated，token 可走 query access_token）；连接建立后经
 * {@link ResourceAccessResolver} 做双模身份解析 + 分配表边界校验（租户 token→tenant client，admin→admin client）。
 * 文本帧 JSON 协议：
 * <ul>
 *   <li>C→S: {"type":"input","data":"..."} / {"type":"resize","cols":N,"rows":M}</li>
 *   <li>S→C: {"type":"output","data":"..."} / {"type":"exit","code":N} / {"type":"error","message":"..."}</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PodExecWebSocketHandler extends TextWebSocketHandler {

    private final KubernetesClientFactory clientFactory;
    private final ResourceAccessResolver accessResolver;
    private final JsonMapper jsonMapper;

    /**stdin 写入独立线程池：管道缓冲满时不阻塞 WS 消息线程 */
    private final ExecutorService stdinExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "pod-exec-stdin");
        t.setDaemon(true);
        return t;
    });

    private record ExecState(ExecWatch watch, PipedOutputStream stdin, AtomicBoolean closed) {
    }

    private final Map<String, ExecState> states = new HashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String tenantId;
        String clusterId;
        String namespace;
        String name;
        String container;
        int cols;
        int rows;
        try {
            Map<String, String> q = queryParams(session.getUri());
            //tenantId 可选：租户 token 从 claim 取；admin token 必须显式传（代操作）
            tenantId = q.get("tenantId");
            clusterId = require(q, "clusterId");
            namespace = require(q, "namespace");
            name = require(q, "name");
            container = q.get("container");
            cols = parseInt(q.get("cols"), 80);
            rows = parseInt(q.get("rows"), 24);

            AccessContext ctx = accessResolver.resolveNamespacedAccess(tenantId, clusterId, namespace);

            KubernetesClient client = ctx.adminMode()
                    ? clientFactory.getAdminClient(clusterId)
                    : clientFactory.getTenantClient(clusterId, ctx.tenantId());
            var pod = client.pods().inNamespace(namespace).withName(name);
            if (StringUtils.hasText(container)) {
                pod.inContainer(container);
            }

            PipedInputStream stdinIn = new PipedInputStream(8192);
            PipedOutputStream stdinOut = new PipedOutputStream(stdinIn);
            AtomicBoolean closed = new AtomicBoolean(false);
            OutputStream sink = new WsSink(session, closed);

            ExecWatch watch = pod.readingInput(stdinIn)
                    .writingOutput(sink)
                    .writingError(sink)
                    .withTTY()
                    .exec("sh");

            states.put(session.getId(), new ExecState(watch, stdinOut, closed));
            if (cols > 0 && rows > 0) {
                watch.resize(cols, rows);
            }
            watch.exitCode().whenComplete((code, err) -> {
                sendJson(session, Map.of("type", "exit", "code", code == null ? -1 : code), closed);
                cleanup(session.getId());
            });
        } catch (CloudPlatformException e) {
            log.warn("Pod exec 拒绝：{}/{} {}", session.getRemoteAddress(), session.getUri(), e.getMessage());
            sendJson(session, Map.of("type", "error", "message", e.getMessage() == null ? "边界校验失败" : e.getMessage()), new AtomicBoolean(false));
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
        } catch (Exception e) {
            log.error("Pod exec 建立失败：{}", session.getUri(), e);
            sendJson(session, Map.of("type", "error", "message", "exec 建立失败: " + e.getMessage()), new AtomicBoolean(false));
            closeQuietly(session, CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ExecState state = states.get(session.getId());
        if (state == null || state.closed().get()) {
            return;
        }
        try {
            JsonNode node = jsonMapper.readTree(message.getPayload());
            String type = node.path("type").asText("");
            switch (type) {
                case "input" -> {
                    byte[] bytes = node.path("data").asText("").getBytes(StandardCharsets.UTF_8);
                    if (bytes.length > 0) {
                        PipedOutputStream stdin = state.stdin();
                        stdinExecutor.submit(() -> {
                            try {
                                synchronized (stdin) {
                                    stdin.write(bytes);
                                }
                            } catch (IOException e) {
                                //进程已退出/管道关闭：忽略
                            }
                        });
                    }
                }
                case "resize" -> {
                    int cols = node.path("cols").asInt(0);
                    int rows = node.path("rows").asInt(0);
                    if (cols > 0 && rows > 0) {
                        state.watch().resize(cols, rows);
                    }
                }
                default -> log.debug("忽略未知 exec 消息类型: {}", type);
            }
        } catch (Exception e) {
            log.warn("exec 消息解析失败：{}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        cleanup(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("exec WS 传输错误 {}: {}", session.getId(), exception.getMessage());
        cleanup(session.getId());
    }

    private void cleanup(String sessionId) {
        ExecState state = states.remove(sessionId);
        if (state == null) {
            return;
        }
        state.closed().set(true);
        try {
            state.stdin().close();
        } catch (IOException ignored) {
        }
        try {
            state.watch().close();
        } catch (Exception e) {
            log.debug("exec 关闭异常: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        states.keySet().forEach(this::cleanup);
        stdinExecutor.shutdownNow();
    }

    // ==================== 消息发送（WebSocketSession 非线程安全，统一加锁） ====================

    /**exec stdout/stderr → WS output 帧的 OutputStream 适配 */
    private final class WsSink extends OutputStream {
        private final WebSocketSession session;
        private final AtomicBoolean closed;

        private WsSink(WebSocketSession session, AtomicBoolean closed) {
            this.session = session;
            this.closed = closed;
        }

        @Override
        public void write(int b) {
            sendOutput(session, new byte[]{(byte) b}, closed);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            sendOutput(session, java.util.Arrays.copyOfRange(b, off, off + len), closed);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private void sendOutput(WebSocketSession session, byte[] bytes, AtomicBoolean closed) {
        if (closed.get() || !session.isOpen()) {
            return;
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        sendJson(session, Map.of("type", "output", "data", text), closed);
    }

    private void sendJson(WebSocketSession session, Map<String, Object> payload, AtomicBoolean closed) {
        if (closed.get() || !session.isOpen()) {
            return;
        }
        try {
            byte[] bytes = jsonMapper.writeValueAsBytes(payload);
            synchronized (session) {
                session.sendMessage(new TextMessage(new String(bytes, StandardCharsets.UTF_8)));
            }
        } catch (Exception e) {
            log.debug("exec 消息发送失败: {}", e.getMessage());
        }
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException ignored) {
        }
    }

    // ==================== query 解析 ====================

    private Map<String, String> queryParams(URI uri) {
        Map<String, String> params = new HashMap<>();
        String query = uri.getRawQuery();
        if (query == null) {
            return params;
        }
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            try {
                params.put(java.net.URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return params;
    }

    private String require(Map<String, String> params, String key) {
        String value = params.get(key);
        if (!StringUtils.hasText(value)) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "缺少参数: " + key);
        }
        return value;
    }

    private int parseInt(String value, int defaultValue) {
        if (!StringUtils.hasText(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

}
