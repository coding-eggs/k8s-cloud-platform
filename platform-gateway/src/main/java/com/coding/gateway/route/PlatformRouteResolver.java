package com.coding.gateway.route;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 外部路径 → (路由, 服务内路径)。按外部前缀最长优先，与配置书写顺序无关。 */
public class PlatformRouteResolver {

    /** @param internalPath 服务内路径（权限表按它匹配） */
    public record ResolvedRoute(PlatformRouteProperties.Route route, String internalPath) {

        /** 上游实际收到的路径 = context-path + 服务内路径 */
        public String forwardPath() {
            return route.contextPath() + internalPath;
        }
    }

    private final List<PlatformRouteProperties.Route> routes;

    public PlatformRouteResolver(List<PlatformRouteProperties.Route> routes) {
        this.routes = routes.stream()
                .sorted(Comparator.comparingInt((PlatformRouteProperties.Route r) -> r.externalPrefix().length()).reversed())
                .toList();
    }

    public Optional<ResolvedRoute> resolve(String externalPath) {
        for (PlatformRouteProperties.Route r : routes) {
            if (externalPath.startsWith(r.externalPrefix())) {
                String rest = externalPath.substring(r.externalPrefix().length());
                return Optional.of(new ResolvedRoute(r, r.internalPrefix() + rest));
            }
        }
        return Optional.empty();
    }

    /** 按前缀长度降序排好的路由（生成 SCG 路由时用同一顺序） */
    public List<PlatformRouteProperties.Route> routes() {
        return routes;
    }
}
