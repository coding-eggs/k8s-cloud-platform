package com.coding.common.components.permission;

/**
 * 权限行的 {@code service} 取值 = 网关路由的 service（spec §8.2）。同一路径在不同服务里是不同端点。
 */
public final class PlatformServices {

    public static final String API = "platform-api";

    public static final String AUTH = "platform-auth";

    private PlatformServices() {}
}
