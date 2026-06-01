package com.coding.common.utils;

import com.nimbusds.jose.Algorithm;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.OctetKeyPairGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;
import java.security.KeyPair;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

@Slf4j
public class KeyGenerator {


    public static String generateSigningKey(String sigAlg, Curve curve, String keyId) throws JOSEException {

        JWSAlgorithm jwsAlgorithm = JWSAlgorithm.parse(sigAlg);

        if (JWSAlgorithm.Family.RSA.contains(jwsAlgorithm)) {

            int keySize = switch (sigAlg.toUpperCase()) {
                case "RS256" -> 2048;
                case "RS384" -> 3072;
                case "RS512" -> 4096;
                default -> 2048;
            };

            RSAKey rsaJWK = new RSAKeyGenerator(keySize)
                    .keyID(keyId)
                    .algorithm(jwsAlgorithm)                 // ✅ 正确：签名算法
                    .keyUse(KeyUse.SIGNATURE)               // ✅ 固定为 sig
                    .generate();

            return rsaJWK.toJSONString();

        } else if (JWSAlgorithm.Family.EC.contains(jwsAlgorithm)) {

            ECKey ecJWK = new ECKeyGenerator(curve)
                    .keyID(keyId)
                    .algorithm(jwsAlgorithm)
                    .keyUse(KeyUse.SIGNATURE)
                    .generate();

            return ecJWK.toJSONString();

        } else if (JWSAlgorithm.Family.ED.contains(jwsAlgorithm)) {

            OctetKeyPair jwk = new OctetKeyPairGenerator(curve)
                    .keyID(keyId)
                    .algorithm(jwsAlgorithm)
                    .keyUse(KeyUse.SIGNATURE)
                    .generate();

            return jwk.toJSONString();
        }

        throw new JWKException("不支持的签名算法");
    }

    public static String generateEncryptionKey(String alg, String keyId, Curve curve) throws JOSEException {

        JWEAlgorithm jweAlgorithm = JWEAlgorithm.parse(alg);

        if (JWEAlgorithm.Family.RSA.contains(jweAlgorithm)) {

            int keySize = switch (alg.toUpperCase()) {
                case "RSA-OAEP-256" -> 2048;
                case "RSA-OAEP" -> 2048;
                default -> 2048;
            };

            RSAKey rsaJWK = new RSAKeyGenerator(keySize)
                    .keyID(keyId)
                    .algorithm(jweAlgorithm)              // ✅ 正确：加密算法
                    .keyUse(KeyUse.ENCRYPTION)           // ✅ 固定为 enc
                    .generate();

            return rsaJWK.toJSONString();
        }

        // 👉 EC 加密（ECDH-ES）
        if (JWEAlgorithm.Family.ECDH_ES.contains(jweAlgorithm)) {

            ECKey ecKey = new ECKeyGenerator(curve)
                    .keyID(keyId)
                    .algorithm(jweAlgorithm)
                    .keyUse(KeyUse.ENCRYPTION)
                    .generate();

            return ecKey.toJSONString();
        }

        throw new JWKException("不支持的加密算法");
    }



    /**
     * keyFactory 所需要的实例化类型
     * @param alg 签名算法名称 / 密钥管理方法名称
     * @return keyFactory 所需要的实例化类型
     */
    public static String getKeyAlgorithm(String alg) throws JWKException {
        if (alg.startsWith("RS")) {
            return "RSA";
        } else if (alg.startsWith("ES")) {
            return "EC";
        } else if (alg.startsWith("HS")) {
            return "HMAC";
        } else if (alg.equals("Ed25519")) {
            return "Ed25519";
        }else if (alg.startsWith("EC")) {
            //密钥管理方法
            return "EC";
        }
        throw new JWKException("不支持的算法类型: " + alg);
    }

    /**
     * 生成HMAC密钥（用于HS256/384/512）
     */
    public static SecretKey generateAesKey(int keySize) throws NoSuchAlgorithmException {
        javax.crypto.KeyGenerator keyGen = javax.crypto.KeyGenerator.getInstance("AES");
        keyGen.init(keySize, SecureRandom.getInstanceStrong());
        return keyGen.generateKey();
    }


    @Data
    @AllArgsConstructor
    public static class StaticKeyPair {
        private String privateKey;
        private String publicKey;
    }

    public static void main(String[] args) throws JOSEException, NoSuchAlgorithmException {

        LocalDateTime localDateTime = LocalDateTime.now();
        String date = localDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        System.out.println(ULIDGenerator.generateULID());
        KeyUse keyUse = KeyUse.SIGNATURE;
        String alg = "ES512";
        Curve curve = Curve.P_521;
//        String key = generateKeyPair(alg, curve,
//                alg + "-" + keyUse.getValue() + "-" + date, keyUse);

//        System.out.println(key);


    }

}
