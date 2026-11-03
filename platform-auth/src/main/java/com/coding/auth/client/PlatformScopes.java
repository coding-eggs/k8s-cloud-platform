package com.coding.auth.client;

import java.util.List;

/**
 * 平台对外提供的 scope 目录 —— 唯一来源：管理页的可选项、授权确认页的文案都从这里取。
 *
 * <p>只放平台真正兑现的 scope：外部客户端拿到的 token 目前只有 sub + name。
 * 旧管理页的 email / phone / address / message.* 没有对应数据，经用户确认先去掉，
 * 要用时再连同含义一起补回来；老客户端身上已有的这些 scope 保存时原样保留。
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
