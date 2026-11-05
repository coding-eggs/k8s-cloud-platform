package com.coding.gateway.route;

import com.coding.common.audit.AuditHeaders;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.ServerHttpRequest;

import java.util.List;

/**
 * 由 {@link PlatformRouteProperties} 生成 SCG 路由。转发路径用 {@link PlatformRouteResolver} 算，
 * 与鉴权看到的服务内路径是同一个结果；命中哪条路由也由它判，两边不可能各认一个服务。
 * query 原样保留（api 的 exec 中继要从 query 取 access_token）。转发时附 {@link AuditHeaders#ORIGINAL_PATH}，
 * 上游审计据此记外部路径，与网关写的 DENIED 同口径。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PlatformRouteProperties.class)
public class PlatformRouteLocatorConfig {

    @Bean
    public PlatformRouteResolver platformRouteResolver(PlatformRouteProperties props) {
        return new PlatformRouteResolver(props.routes());
    }

    @Bean
    public RouteLocator platformRoutes(RouteLocatorBuilder builder, PlatformRouteResolver resolver) {
        RouteLocatorBuilder.Builder routes = builder.routes();
        List<PlatformRouteProperties.Route> ordered = resolver.routes();
        for (int i = 0; i < ordered.size(); i++) {
            PlatformRouteProperties.Route r = ordered.get(i);
            int order = i;
            routes.route(r.service() + ":" + r.externalPrefix(), spec -> spec
                    .order(order)
                    // 命中判定直接用 resolver（不用 Path 断言）：`/x/**` 也会匹配不带斜杠的 `/x`，
                    // 那样转发与鉴权会各认一个服务（请求发去 auth、却按 api 的权限行判）
                    .predicate(exchange -> resolver.resolve(exchange.getRequest().getPath().value())
                            .map(resolved -> resolved.route() == r).orElse(false))
                    .filters(f -> f.filter((exchange, chain) -> {
                        ServerHttpRequest req = exchange.getRequest();
                        String external = req.getPath().value();
                        String forward = resolver.resolve(external).orElseThrow().forwardPath();
                        ServerWebExchangeUtils.addOriginalRequestUrl(exchange, req.getURI());
                        // 覆写（不是追加）：客户端自带的同名头不能冒充；不含 query（WS 的 access_token 在 query 里）
                        ServerHttpRequest forwarded = req.mutate().path(forward)
                                .headers(h -> h.set(AuditHeaders.ORIGINAL_PATH, external)).build();
                        return chain.filter(exchange.mutate().request(forwarded).build());
                    }))
                    .uri(r.uri()));
        }
        return routes.build();
    }
}
