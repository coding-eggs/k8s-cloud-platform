package com.coding.auth.client;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Set;

@Data
@Schema(title = "OAuth2 客户端注册请求", description = "用于创建和更新 RegisteredClient")
public class RegisteredClientReq {

    @Schema(description = "客户端内部ID（更新时必传，创建时可不传）", example = "c1f8e9d2-...")
    private String id;

    @Schema(description = "客户端ID（Client ID），全局唯一", example = "my-backend-service", requiredMode = Schema.RequiredMode.REQUIRED)
    private String clientId;

    @Schema(description = "客户端显示名称", example = "内部后台管理系统")
    private String clientName;

    @Schema(description = "客户端密钥（明文），创建时建议填写，更新时不传则不修改。如果clientAuthenticationMethods 为 client_secret_jwt 则传入对称密钥", example = "myStrongSecret123!")
    private String clientSecret;

    @Schema(description = "客户端密钥加密时的 kid（用于后端解密）")
    private String clientSecretKid;

    @Schema(description = "客户端密钥过期时间", example = "2027-12-31T23:59:59")
    private LocalDateTime clientSecretExpiresAt;

    @Schema(description = "支持的授权类型", example = "[\"authorization_code\", \"refresh_token\", \"client_credentials\"]")
    private Set<String> authorizationGrantTypes;

    @Schema(description = "可申请的权限范围", example = "[\"openid\", \"profile\", \"email\", \"read:order\"]")
    private Set<String> scopes;

    @Schema(description = "授权回调地址（Redirect URIs）", example = "[\"https://myapp.com/login/oauth2/code/client\"]")
    private Set<String> redirectUris;

    @Schema(description = "登出后重定向地址", example = "[\"https://myapp.com/logout\"]")
    private Set<String> postLogoutRedirectUris;

    @Schema(description = "客户端认证方式",
            example = "[\"client_secret_basic\", \"private_key_jwt\"]",
            allowableValues = {"client_secret_basic", "client_secret_post", "private_key_jwt", "none"})
    private Set<String> clientAuthenticationMethods;

    // ====================== 自定义扩展配置 ======================

    @Schema(description = "JWT 类型", allowableValues = {"JWS", "JWE"}, defaultValue = "JWS")
    private String jwtType;

    // ==================== JWE 配置 ====================

    @Schema(description = "JWE 模式下客户端公钥地址（JWK Set URL）",
            example = "https://client.example.com/.well-known/jwks.json")
    private String jweJwkUrl;

    @Schema(description = "JWE Key Encryption Algorithm（密钥加密算法）",
            example = "RSA-OAEP-256",
            allowableValues = {"RSA-OAEP-256", "dir"})
    private String jweKeyAlg;

    @Schema(description = "JWE Content Encryption Method（内容加密算法）",
            example = "A256GCM",
            allowableValues = {"A256GCM", "A192GCM", "A128GCM"})
    private String jweEncMethod;

    @Schema(description = "JWE 对称加密密钥（仅当 jweKeyAlg=dir 时使用）",
            example = "Base64编码的32字节密钥")
    private String jweSecret;


    private String jweSecretKid;

    // ==================== JWS 对称加密配置 ====================

    @Schema(description = "JWS 签名算法（仅当使用对称签名 HS256/HS384/HS512 时需要）",
            example = "HS256")
    private String jwsSigAlg;

    @Schema(description = "JWS 对称签名密钥（仅当使用对称签名时需要）",
            example = "Base64编码的密钥")
    private String jwsSecret;

    private String jwsSecretKid;

    // ==================== 标准 ClientSettings ====================

    @Schema(description = "是否强制要求使用 PKCE", defaultValue = "true")
    private Boolean requireProofKey;

    @Schema(description = "是否每次授权都要求用户手动同意", defaultValue = "false")
    private Boolean requireAuthorizationConsent;

    // ==================== TokenSettings ====================

    @Schema(description = "Access Token 有效期（秒）", example = "3600", defaultValue = "3600")
    private Long accessTokenTimeToLive;

    @Schema(description = "Refresh Token 有效期（秒）", example = "86400", defaultValue = "86400")
    private Long refreshTokenTimeToLive;

}
