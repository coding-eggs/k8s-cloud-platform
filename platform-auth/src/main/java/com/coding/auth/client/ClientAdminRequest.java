package com.coding.auth.client;

import lombok.Data;

import java.util.Set;

/**
 * 内部接口的创建 / 更新请求（platform-api 原样转发）。
 *
 * <p>刻意没有 builtin / enabled / 任何密钥字段：内置标记只由迁移打，启用只经 enable 接口，
 * 对称密钥一律由 auth 生成。
 */
@Data
public class ClientAdminRequest {

    /** 更新时必填 */
    private String id;
    /** 创建时可空（生成 ext- 前缀）；更新时忽略 */
    private String clientId;
    private String clientName;
    /** 创建时必填；更新时忽略 */
    private ClientType type;
    private Set<String> grantTypes;
    /** client_secret_basic / client_secret_post / private_key_jwt / none；机密客户端至少一种非 none */
    private Set<String> clientAuthenticationMethods;
    private Set<String> scopes;
    private Set<String> redirectUris;
    private Set<String> postLogoutRedirectUris;
    /** 启用授权码时是否强制 PKCE；公共客户端恒为 true */
    private Boolean requireProofKey;
    private Boolean requireAuthorizationConsent;
    /** 秒 */
    private Long accessTokenTimeToLive;
    /** 秒 */
    private Long refreshTokenTimeToLive;
    /** JWS | JWE */
    private String jwtType;
    private String jwsSigAlg;
    private String jweKeyAlg;
    private String jweEncMethod;
    /** 对方的 JWK Set 地址：private_key_jwt 认证与非对称 JWE 共用 */
    private String jweJwkUrl;
}
