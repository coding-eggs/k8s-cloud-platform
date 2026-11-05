package com.coding.gateway.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 与 platform-api / k8s-server 的同名类同一组键（各服务各一份，不抽公共类）。
 * 网关透传的是同一个 token，三处必须配成同一种 type / 同一把密钥。
 */
@Data
@ConfigurationProperties(prefix = "spring.security.oauth2.resourceserver.jwt")
public class OAuth2JwtProperties {

    /** JWE = 对称加密的 JWE；其它（含不配）= JWS */
    private String type;

    /** 允许的 aud（平台自有客户端的 client_id）。为空拒启。 */
    private List<String> audiences;

    /** 配了 = 对称签名（HMAC）；不配 = 按 jwk-set-uri 验非对称签名 */
    private Secret jws;

    /** type=JWE 时：对称解密密钥；内层 JWS 仍按 jwk-set-uri 验签 */
    private Secret jwe;

    @Data
    public static class Secret {
        private String secret;

        private String secretAlg;

        private String encMethod;
    }
}
