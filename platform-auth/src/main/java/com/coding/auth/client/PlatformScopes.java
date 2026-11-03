package com.coding.auth.client;

import java.util.List;

/**
 * 平台对外提供的 scope 目录 —— 唯一来源：管理页的可选项、授权确认页的文案都从这里取。
 *
 * <p>沿用旧客户端管理页的完整目录（不因「token 里暂时没有对应数据」而删减）；
 * 外部客户端拿到的 token 目前只有 sub + name。
 */
public final class PlatformScopes {

    public static final List<OAuth2Scope> CATALOG = List.of(
            new OAuth2Scope("openid", "认证用户身份并获取登录状态"),
            new OAuth2Scope("profile", "查看你的显示名"),
            new OAuth2Scope("email", "查看并使用你的邮箱地址"),
            new OAuth2Scope("phone", "查看你的手机号码"),
            new OAuth2Scope("address", "查看你的收货地址"),
            new OAuth2Scope("message.read", "读取你的消息"),
            new OAuth2Scope("message.write", "发送消息"));

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
