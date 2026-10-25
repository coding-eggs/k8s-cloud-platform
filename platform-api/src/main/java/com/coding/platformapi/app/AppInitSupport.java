package com.coding.platformapi.app;

import java.util.Set;

/**
 * 支持初始化的中间件类型（与 k8s-server 的 {@code AppInitScripts} 同一份清单）。
 *
 * <p>为什么在 platform-api 也留一份：编排层要在「跑 Job 之前」判断这个节点该不该有初始化动作，
 * 跨模块引 k8s-server 的类会把两个部署单元绑死。改动时两边一起改（有测试钉住 k8s-server 那侧）。
 */
public final class AppInitSupport {

    private static final Set<String> SUPPORTED =
            Set.of("mysql", "postgresql", "redis", "elasticsearch", "kafka");

    private AppInitSupport() {
    }

    public static boolean supports(String code) {
        return code != null && SUPPORTED.contains(code);
    }
}
