package com.coding.platformapi.models.oauthclient;

import lombok.Data;

import java.util.Set;

/**
 * 创建 / 更新请求，与 auth 的同名 DTO 同形，原样转发。
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
    private Set<String> scopes;
    private Set<String> redirectUris;
    private Set<String> postLogoutRedirectUris;
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
    private String jweJwkUrl;
}
