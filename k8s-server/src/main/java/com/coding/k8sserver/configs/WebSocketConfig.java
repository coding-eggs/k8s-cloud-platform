package com.coding.k8sserver.configs;

import com.coding.k8sserver.components.PodExecWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 端点注册。安全由既有 SecurityFilterChain 承担：
 * /ws/** 命中 anyRequest().authenticated()（双模：租户 token 或 admin token），
 * JWT token 可走 query access_token；命名空间边界在 handler 内经 ResourceAccessResolver 校验。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final PodExecWebSocketHandler podExecWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(podExecWebSocketHandler, "/ws/pod/exec")
                .setAllowedOriginPatterns("*");
    }

}
