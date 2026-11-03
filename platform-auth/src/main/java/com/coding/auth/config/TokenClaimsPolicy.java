package com.coding.auth.config;

import com.coding.auth.client.PlatformClientSettings;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.util.StringUtils;

/**
 * token 里写哪些自定义 claim，按客户端类别分三种（spec §4.3）。
 *
 * <ul>
 *   <li>{@link Kind#PLATFORM_USER} —— 内置客户端 + 用户：照旧写 {@code data}（平台角色、租户上下文…），
 *       资源服务靠它鉴权。</li>
 *   <li>{@link Kind#EXTERNAL_USER} —— 外部客户端 + 用户：只给 {@code sub} + {@code name}（显示名）。
 *       平台内部信息不外泄；没有 {@code data} 的 token 在平台资源服务也过不了 aud。</li>
 *   <li>{@link Kind#NO_USER} —— client_credentials：principal 是客户端本身，不查用户、不写任何自定义 claim。
 *       旧实现在这里无条件强转 {@code User}，会直接 500。</li>
 * </ul>
 */
public final class TokenClaimsPolicy {

    public enum Kind { PLATFORM_USER, EXTERNAL_USER, NO_USER }

    private TokenClaimsPolicy() {
    }

    public static Kind classify(RegisteredClient client, Object principal) {
        if (!(principal instanceof User)) {
            return Kind.NO_USER;
        }
        return PlatformClientSettings.isBuiltin(client) ? Kind.PLATFORM_USER : Kind.EXTERNAL_USER;
    }

    /** 外部 token 的 name：显示名为空时回落用户名 */
    public static String externalDisplayName(String displayName, String username) {
        return StringUtils.hasText(displayName) ? displayName : username;
    }
}
