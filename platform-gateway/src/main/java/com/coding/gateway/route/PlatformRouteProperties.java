package com.coding.gateway.route;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * 路由表（spec §4、§8.2）。**鉴权与转发共用这一份**：外部路径先解析成 (service, 服务内路径)，
 * 权限表按服务内路径匹配，转发也按它拼上游路径，两边不可能对不上。
 *
 * @param routes                   书写顺序无关，解析按外部前缀最长优先
 * @param permissionReloadInterval 权限表定时重载间隔（权限点管理页改行后，网关最迟这么久生效）
 */
@ConfigurationProperties(prefix = "platform.gateway")
public record PlatformRouteProperties(List<Route> routes, Duration permissionReloadInterval) {

    public PlatformRouteProperties {
        routes = routes == null ? List.of() : List.copyOf(routes);
        permissionReloadInterval = permissionReloadInterval == null ? Duration.ofSeconds(60) : permissionReloadInterval;
    }

    /**
     * @param service        权限行的 service（platform-api / platform-auth）
     * @param externalPrefix nginx 去掉 /api 之后网关看到的前缀，以 {@code /} 结尾
     * @param internalPrefix 服务内路径前缀（不含 context-path），权限行的 resource 按它写
     * @param uri            上游地址（scheme://host:port）
     * @param contextPath    上游的 servlet context-path（auth 是 /auth，api 为空）
     */
    public record Route(String service, String externalPrefix, String internalPrefix, URI uri, String contextPath) {

        public Route {
            contextPath = contextPath == null ? "" : contextPath;
        }
    }
}
