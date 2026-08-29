package com.coding.platformapi.configs;

import com.coding.platformapi.components.PodExecRelayHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 端点注册。安全由既有 SecurityFilterChain 承担：
 * /ws/** 命中 anyRequest().hasAuthority(PLATFORM:admin)，JWT token 走 query access_token；
 * 命名空间边界校验在上游 k8s-server 执行（ResourceAccessResolver）。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final PodExecRelayHandler podExecRelayHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(podExecRelayHandler, "/ws/pod/exec")
                .setAllowedOriginPatterns("*");
    }

}
