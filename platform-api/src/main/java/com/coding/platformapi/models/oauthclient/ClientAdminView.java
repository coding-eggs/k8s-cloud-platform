package com.coding.platformapi.models.oauthclient;

import lombok.Data;

import java.time.Instant;
import java.util.Set;

/** 与 auth 的同名 DTO 同形（转发用）。客户端详情 —— 绝不含任何密钥材料（明文、密文、哈希都不给），只有「有没有」与 kid */
@Data
public class ClientAdminView {

    private String id;
    private String clientId;
    private String clientName;
    private ClientType type;
    private boolean builtin;
    private boolean enabled;
    private Set<String> grantTypes;
    private Set<String> clientAuthenticationMethods;
    private Set<String> scopes;
    private Set<String> redirectUris;
    private Set<String> postLogoutRedirectUris;
    private boolean requireProofKey;
    private boolean requireAuthorizationConsent;
    private long accessTokenTimeToLive;
    private Long refreshTokenTimeToLive;
    private String jwtType;
    private String jwsSigAlg;
    private String jweKeyAlg;
    private String jweEncMethod;
    private String jweJwkUrl;
    private boolean hasClientSecret;
    private boolean hasJwsSecret;
    private boolean hasJweSecret;
    private String jwsSecretKid;
    private String jweSecretKid;
    private Instant clientIdIssuedAt;
}
