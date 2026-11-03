package com.coding.auth.service;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWSAlgorithm;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Set;

/**
 * token 形态（JWS / JWE、对称 / 非对称）的合法取值与对称密钥长度。
 *
 * <p>对称密钥在签发时按 {@code secret.getBytes(UTF_8)} 使用（见 jwtEncoder / CustomJweEncoder），
 * 所以生成的是「字符数 = 所需字节数」的字母数字串。
 */
final class ClientTokenForms {

    static final String JWS = "JWS";
    static final String JWE = "JWE";
    static final String DEFAULT_JWS_ALG = "RS256";
    static final String DEFAULT_ENC = "A256GCM";

    private static final Map<String, Integer> HMAC_BYTES = Map.of("HS256", 32, "HS384", 48, "HS512", 64);

    private static final Map<String, Integer> KW_BYTES = Map.of("A128KW", 16, "A192KW", 24, "A256KW", 32);

    /** dir 模式下密钥直接当 CEK，长度由内容加密算法决定 */
    private static final Map<String, Integer> ENC_BYTES = Map.of(
            "A128GCM", 16, "A192GCM", 24, "A256GCM", 32,
            "A128CBC-HS256", 32, "A192CBC-HS384", 48, "A256CBC-HS512", 64);

    private static final String ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final String LOWER_ALNUM = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private ClientTokenForms() {
    }

    static boolean isHmac(String jwsAlg) {
        return jwsAlg != null && HMAC_BYTES.containsKey(jwsAlg);
    }

    static boolean isSymmetricJwe(String keyAlg) {
        return keyAlg != null && ("dir".equals(keyAlg) || KW_BYTES.containsKey(keyAlg));
    }

    static void checkJwsAlg(String alg) {
        if (isHmac(alg)) return;
        JWSAlgorithm a = JWSAlgorithm.parse(alg);
        if (!JWSAlgorithm.Family.RSA.contains(a) && !JWSAlgorithm.Family.EC.contains(a)) {
            throw invalid("不支持的签名算法 " + alg);
        }
    }

    static void checkJwe(String keyAlg, String enc, String jwkUrl) {
        if (keyAlg == null) throw invalid("JWE 必须选择密钥管理算法");
        if (!ENC_BYTES.containsKey(enc)) throw invalid("不支持的内容加密算法 " + enc);
        if (isSymmetricJwe(keyAlg)) return;
        JWEAlgorithm a = JWEAlgorithm.parse(keyAlg);
        if (!JWEAlgorithm.Family.RSA.contains(a) && !JWEAlgorithm.Family.ECDH_ES.contains(a)) {
            throw invalid("不支持的密钥管理算法 " + keyAlg);
        }
        if (jwkUrl == null || jwkUrl.isBlank()) throw invalid("JWE 非对称加密必须填写对方的公钥地址（JWK Set URL）");
    }

    static int jwsSecretBytes(String alg) {
        return HMAC_BYTES.get(alg);
    }

    static int jweSecretBytes(String keyAlg, String enc) {
        return "dir".equals(keyAlg) ? ENC_BYTES.get(enc) : KW_BYTES.get(keyAlg);
    }

    static String randomAlnum(int len) {
        return random(ALNUM, len);
    }

    static String randomClientId() {
        return "ext-" + random(LOWER_ALNUM, 12);
    }

    static boolean anyOf(String v, Set<String> allowed) {
        return v != null && allowed.contains(v);
    }

    static CloudPlatformException invalid(String why) {
        return new CloudPlatformException(EnumResponseType.OAUTH_CLIENT_INVALID,
                EnumResponseType.OAUTH_CLIENT_INVALID.getMsg() + why);
    }

    private static String random(String alphabet, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        return sb.toString();
    }
}
