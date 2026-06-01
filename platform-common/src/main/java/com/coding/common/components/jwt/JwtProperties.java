package com.coding.common.components.jwt;


import com.nimbusds.jose.EncryptionMethod;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Data
@Configuration
@ConfigurationProperties(prefix = "jwt")
@ConditionalOnProperty(
        prefix = "jwt",
        value = "enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class JwtProperties {


    private boolean enabled = false;

    /**
     * JWT 类型: JWS(签名) 或 JWE(加密)
     */
    private String type = "JWS";

    /**
     * 发行者
     */
    private String issuer;


    private String dataKey = "data";

    /**
     * 令牌前缀（如 "Bearer "）
     */
    private String tokenPrefix = "Bearer ";

//    /**
//     * 签名配置（JWS 使用）
//     */
//    private SignatureConfig signature = new SignatureConfig();
//
    /**
     * 加密配置（JWE 使用）
     */
    private EncryptionConfig encryption = new EncryptionConfig();
//
//    /**
//     * 签名配置，用于JWS
//     */
//    @Data
//    public static class SignatureConfig {
//        /**
//         * {@link com.nimbusds.jose.JWSAlgorithm}
//         * 签名算法：
//         * 对称加密：HS256 HS384 HS512
//         * 非对称加密：RS256 RS384 RS512 / ES256 ES384 ES512
//         */
//        private String algorithm = "ES384";
//
//    }
//
//    /**
//     * 加密配置，用于JWE
//     */
    @Data
    public static class EncryptionConfig {
//        /**
//         * {@link com.nimbusds.jose.JWEAlgorithm}
//         * 密钥管理对称算法
//         * DIRECT （此算法不产生密钥，直接使用 secret字段进行加密） 、
//         * A128KW、A192KW、A256KW
//         * A128GCMKW、A192GCMKW、A256GCMKW
//
//         * 密钥管理非对称算法:
//         * RSA-OAEP, RSA-OAEP-256, RSA1_5
//         * ECDH_ES, ECDH_ES_A128KW, ECDH_ES_A192KW, ECDH_ES_A256KW
//
//         * 不建议用对称加密，相对非对称加密来说安全无法完全保证
//         */
//        private String keyAlgorithm = "ECDH_ES_A256KW";

        /**
         * {@link com.nimbusds.jose.EncryptionMethod}
         * 内容加密算法, 用于给数据加密的对称加密算法: A128GCM, A256GCM
         */
        private String contentAlgorithm = "A256GCM";

    }


}
