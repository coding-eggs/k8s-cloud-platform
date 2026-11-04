package com.coding.gateway.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Bearer 从哪取：
 * <ul>
 *   <li>公开路径：<b>不解析</b>。{@code /alert/webhook} 的 {@code Bearer xxx} 是共享密钥不是 JWT，
 *       解析就会在过滤器链层 401，端点自己的密钥校验根本轮不到；</li>
 *   <li>{@code /ws/**}：允许 query {@code access_token}（浏览器 WS 握手带不了头）；</li>
 *   <li>其余：只认 {@code Authorization} 头。api 以前全路径回退 query，网关收窄：token 进 URL 会落进各处访问日志。</li>
 * </ul>
 */
public class PlatformBearerTokenConverter implements ServerAuthenticationConverter {

    private final ServerWebExchangeMatcher publicPaths;
    private final ServerBearerTokenAuthenticationConverter headerOnly = new ServerBearerTokenAuthenticationConverter();
    private final ServerBearerTokenAuthenticationConverter headerOrQuery = new ServerBearerTokenAuthenticationConverter();

    public PlatformBearerTokenConverter(ServerWebExchangeMatcher publicPaths) {
        this.publicPaths = publicPaths;
        headerOrQuery.setAllowUriQueryParameter(true);
    }

    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        return publicPaths.matches(exchange).flatMap(m -> {
            if (m.isMatch()) {
                return Mono.empty();
            }
            boolean ws = exchange.getRequest().getPath().value().startsWith("/ws/");
            return (ws ? headerOrQuery : headerOnly).convert(exchange);
        });
    }
}
