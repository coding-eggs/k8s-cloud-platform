package com.coding.auth.config;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CustomClientSetting {

    public static String CUSTOM_SETTING_KEY = "custom.client.setting";

    //jwt类型 可选 JWE / JWS, 默认是JWS
    public static String CLIENT_SETTING_JWT_TYPE_KEY = "oauth2.jwt.type";

    //客户端提供的jwk url ，只有在 jwe模式需要客户端提供
    public static String CLIENT_SETTING_JWK_URL_KEY = "oauth2.jwe.jwk-url";

    //客户端需要jwe签发token时需要客户端提供的 key管理算法
    public static String CLIENT_SETTING_JWE_KEY_ALG_KEY = "oauth2.jwe.key-alg";

    //客户端提供的 内容加密算法
    public static String CLIENT_SETTING_JWE_ENC_METHOD_KEY = "oauth2.jwe.enc-method";

    //客户端需要签发jwe
    public static String CLIENT_SETTING_JWE_SECRET_KEY = "oauth2.jwe.secret";
    //前端传入后端存储时使用加密的kid
    public static String CLIENT_SETTING_JWE_SECRET_KID_KEY = "oauth2.jwe.secret.key-id";

    //jws签名算法，只有对称加密时才由客户端指定
    public static String CLIENT_SETTING_JWS_SIG_ALG = "oauth2.jws.alg";

    //只有 oauth2.jwt.type 为 jws 且 客户端指定了必须要用 对称算法时，需要客户端提供对称密钥
    public static String CLIENT_SETTING_JWS_SECRET_KEY = "oauth2.jws.secret";
    //前端传入后端存储时使用加密的kid
    public static String CLIENT_SETTING_JWS_SECRET_KID_KEY = "oauth2.jws.secret.key-id";



    private String jwtType;

    private String jweJwkUrl;

    private String jweKeyAlg;

    private String jweEncMethod;

    private String jweSecret;

    private String jweSecretKeyId;

    private String jwsSigAlg;

    private String jwsSecret;

    private String jwsSecretKeyId;

}
