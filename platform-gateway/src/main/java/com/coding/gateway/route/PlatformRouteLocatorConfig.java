package com.coding.gateway.route;

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
 * 与鉴权看到的服务内路径是同一个结果。query 原样保留（api 的 exec 中继要从 query 取 access_token）。
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
                    .path(r.externalPrefix() + "**")
                    .filters(f -> f.filter((exchange, chain) -> {
                        ServerHttpRequest req = exchange.getRequest();
                        // Path 断言已保证命中本路由前缀；按同一个 resolver 算，最长前缀与鉴权一致
                        String forward = resolver.resolve(req.getPath().value()).orElseThrow().forwardPath();
                        ServerWebExchangeUtils.addOriginalRequestUrl(exchange, req.getURI());
                        return chain.filter(exchange.mutate().request(req.mutate().path(forward).build()).build());
                    }))
                    .uri(r.uri()));
        }
        return routes.build();
    }
}
