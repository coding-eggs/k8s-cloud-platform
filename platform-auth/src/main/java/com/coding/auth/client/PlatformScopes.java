package com.coding.auth.client;

import java.util.List;

/**
 * 平台对外提供的 scope 目录 —— 唯一来源：管理页的可选项、授权确认页的文案都从这里取。
 *
 * <p>外部客户端拿到的 token 只有 sub + name，所以目录里只有这两个；以后开放平台接口时
 * 再在这里加业务 scope（spec §8）。
 */
public final class PlatformScopes {

    public static final List<OAuth2Scope> CATALOG = List.of(
            new OAuth2Scope("openid", "认证用户身份并获取登录状态"),
            new OAuth2Scope("profile", "查看你的显示名"));

    private PlatformScopes() {
    }

    public static boolean contains(String scope) {
        return CATALOG.stream().anyMatch(s -> s.getName().equals(scope));
    }

    /** 目录外的 scope（老客户端可能还带着）回落成通用文案，确认页不报错 */
    public static OAuth2Scope describe(String scope) {
        return CATALOG.stream()
                .filter(s -> s.getName().equals(scope))
                .findFirst()
                .orElseGet(() -> new OAuth2Scope(scope, scope + " 权限"));
    }
}
