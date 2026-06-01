package com.coding.auth.client;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.Instant;
import java.util.Set;

/**
 * OAuth2 客户端查询响应对象
 */
@Data
@Schema(title = "OAuth2 客户端信息响应", description = "客户端查询返回对象")
public class RegisteredClientRes {

    @Schema(description = "内部主键ID（用于更新/删除）")
    private String id;

    @Schema(description = "客户端标识（Client ID）")
    private String clientId;

    @Schema(description = "客户端名称")
    private String clientName;

    @Schema(description = "客户端ID签发时间")
    private Instant clientIdIssuedAt;

    @Schema(description = "客户端密钥过期时间")
    private Instant clientSecretExpiresAt;

    @Schema(description = "支持的授权类型")
    private Set<String> authorizationGrantTypes;

    @Schema(description = "权限范围")
    private Set<String> scopes;

    @Schema(description = "客户端密钥（脱敏展示）")
    private String clientSecret;

    @Schema(description = "授权回调地址")
    private Set<String> redirectUris;

    @Schema(description = "登出回调地址")
    private Set<String> postLogoutRedirectUris;

    @Schema(description = "客户端认证方式")
    private Set<String> clientAuthenticationMethods;

    @Schema(description = "JWT Token 类型", allowableValues = {"JWS", "JWE"})
    private String jwtType;

    @Schema(description = "JWE 公钥地址")
    private String jweJwkUrl;

    @Schema(description = "JWE 密钥加密算法")
    private String jweKeyAlg;

    @Schema(description = "JWE 内容加密算法")
    private String jweEncMethod;

    @Schema(description = "JWS 签名算法")
    private String jwsSigAlg;

    @Schema(description = "JWS 密钥（脱敏展示）")
    private String jwsSecret;

    @Schema(description = "JWS 密钥标识")
    private String jwsSecretKid;

    @Schema(description = "JWE 密钥（脱敏展示）")
    private String jweSecret;

    @Schema(description = "JWE 密钥标识")
    private String jweSecretKid;

    @Schema(description = "是否强制 PKCE")
    private Boolean requireProofKey;

    @Schema(description = "是否需要授权同意")
    private Boolean requireAuthorizationConsent;

    @Schema(description = "Access Token 有效期（秒）")
    private Long accessTokenTimeToLive;

    @Schema(description = "Refresh Token 有效期（秒）")
    private Long refreshTokenTimeToLive;

    @Schema(description = "创建时间")
    private Instant createdAt;

    @Schema(description = "最后更新时间")
    private Instant updatedAt;
}
