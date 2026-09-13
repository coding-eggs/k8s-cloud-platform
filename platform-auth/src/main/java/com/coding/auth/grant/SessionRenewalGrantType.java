package com.coding.auth.grant;

import org.springframework.security.oauth2.core.AuthorizationGrantType;

/**
 * 自定义授权类型：会话续期（Session Renewal）。
 *
 * <p>用于 PKCE 公共客户端（client_authentication_method = none）拿不到 refresh_token 的场景：
 * SPA 以有效的 HttpOnly 会话 Cookie 作为根凭证，在 access_token 过期后通过本 grant 重新签发
 * access_token，而无需重新走 authorization_code 授权流程。
 *
 * <p>grant_type 值使用唯一 URI，避免与标准/第三方 grant 冲突。
 */
public final class SessionRenewalGrantType {

    /** grant_type 参数值（前端表单可选值、provider 比较均引用此常量，务必保持一致） */
    public static final String VALUE = "urn:coding:grant-type:session-renewal";

    /** 供 RegisteredClient / provider 做集合比较使用的常量实例 */
    public static final AuthorizationGrantType INSTANCE = new AuthorizationGrantType(VALUE);

    private SessionRenewalGrantType() {
    }
}
