package com.coding.k8sserver.configs;

import com.coding.k8sserver.components.PodExecWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WebSocket 端点注册。安全由既有 SecurityFilterChain 承担：
 * /ws/** 命中 anyRequest().authenticated()（双模：租户 token 或 admin token），
 * JWT token 可走 query access_token；命名空间边界在 handler 内经 ResourceAccessResolver 校验。
 * <p>
 * 握手经 {@link SecurityContextHandshakeInterceptor} 把认证身份存入 session attributes：
 * afterConnectionEstablished 跑在 Tomcat WS worker 线程，thread-local SecurityContext 已空，只能从 attributes 取。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final PodExecWebSocketHandler podExecWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(podExecWebSocketHandler, "/ws/pod/exec")
                .addInterceptors(new SecurityContextHandshakeInterceptor())
                .setAllowedOriginPatterns("*");
    }

    /**握手期（HTTP 请求线程，SecurityContext 尚在）把 Authentication 存入 session attributes；
     *  afterConnectionEstablished 在 WS worker 线程执行、thread-local 已空，只能从 attributes 取身份。 */
    private static final class SecurityContextHandshakeInterceptor implements HandshakeInterceptor {
        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null) {
                attributes.put(PodExecWebSocketHandler.ATTR_AUTH, auth);
            }
            return true;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {
        }
    }
}
