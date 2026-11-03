package com.coding.auth.client;

import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

/**
 * 平台在 client_settings 里加的两个标记。
 *
 * <ul>
 *   <li>{@link #BUILTIN_KEY} —— 平台自有客户端（目前只有 platform-web-client）。只由迁移打标，
 *       任何管理接口都不能设置或清除；内置客户端不可删除、不可停用，token 带平台用户信息。</li>
 *   <li>{@link #ENABLED_KEY} —— 停用标记，缺省视为启用（老行没有这个键）。</li>
 * </ul>
 */
public final class PlatformClientSettings {

    public static final String BUILTIN_KEY = "platform.client.builtin";

    public static final String ENABLED_KEY = "platform.client.enabled";

    private PlatformClientSettings() {
    }

    public static boolean isBuiltin(RegisteredClient client) {
        return Boolean.TRUE.equals(client.getClientSettings().getSetting(BUILTIN_KEY));
    }

    public static boolean isEnabled(RegisteredClient client) {
        Object v = client.getClientSettings().getSetting(ENABLED_KEY);
        return v == null || Boolean.TRUE.equals(v);
    }

    /** 以 base 的全部设置为底，只改启用标记 */
    public static ClientSettings.Builder withEnabled(ClientSettings base, boolean enabled) {
        return ClientSettings.withSettings(base.getSettings()).setting(ENABLED_KEY, enabled);
    }
}
