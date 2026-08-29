package com.coding.common.components.jwt.impl;


import com.coding.common.components.jwt.JwtProperties;
import com.coding.common.components.jwt.JwtStrategy;
import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.EncryptedJWT;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * jwe 实现jwt 保证机密性、完整性、真实性
 * @param <T>
 */
@Slf4j
@Component
@ConditionalOnBean(JwtProperties.class)
@RequiredArgsConstructor
public class JweTokenStrategy<T> implements JwtStrategy<T> {

    private static final String CLAIM_KEY_TOKEN_ID = "jti";
    private static final String CLAIM_KEY_TYPE = "type";

    private final JwtProperties jwtProperties;
    private final JsonMapper jsonMapper;

    private final JWKSource<SecurityContext> jwkSource;

    @Override
    public String generateToken(T t, String tokenType, long expirationSeconds, String subject) throws NoSuchAlgorithmException,
            JOSEException {

        JwtProperties.EncryptionConfig encryption = jwtProperties.getEncryption();
        Date now = new Date();
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(jwtProperties.getIssuer())
                .expirationTime(new Date(now.getTime() + 1000 * expirationSeconds))
                .notBeforeTime(now)
                .issueTime(now)
                .claim(jwtProperties.getDataKey(), t)
                .claim(CLAIM_KEY_TYPE, tokenType)
                .claim(CLAIM_KEY_TOKEN_ID, UUID.randomUUID().toString())
                .claim("iat", Instant.now().getEpochSecond())
                .build();

        JWK jwk = jwkSource.get(new JWKSelector(new JWKMatcher.Builder()
                .keyUse(KeyUse.ENCRYPTION)
                .build()
        ), null).getFirst();

        return encrypt(claimsSet, encryption.getContentAlgorithm(), jwk);
    }

    /**
     *
     * @param claimsSet 要加密的数据
     * @param encMethod 内容加密算法
     * @param jwk jwk
     */
    public String encrypt (JWTClaimsSet claimsSet, String encMethod , JWK jwk)
            throws JOSEException, NoSuchAlgorithmException {

        //密钥管理算法
        String keyAlgorithm = jwk.getAlgorithm().getName();
        JWEAlgorithm jweKeyAlgorithm = JWEAlgorithm.parse(keyAlgorithm);
        JWEHeader jweHeader = new JWEHeader(JWEAlgorithm.parse(keyAlgorithm),
                EncryptionMethod.parse(encMethod));

        EncryptedJWT encryptedJWT = new EncryptedJWT(jweHeader, claimsSet);

        if (JWEAlgorithm.Family.AES_KW.contains(jweKeyAlgorithm) ||
                JWEAlgorithm.Family.AES_GCM_KW.contains(jweKeyAlgorithm)) {
            if (jwk instanceof OctetSequenceKey octetSequenceKey) {
                AESEncrypter aesEncrypter = new AESEncrypter(octetSequenceKey);
                encryptedJWT.encrypt(aesEncrypter);
            }
        } else if (JWEAlgorithm.Family.RSA.contains(jweKeyAlgorithm)) {
            if (jwk instanceof RSAKey rsaKey) {
                RSAEncrypter rsaEncrypter = new RSAEncrypter(rsaKey);
                encryptedJWT.encrypt(rsaEncrypter);
            }
        } else if (JWEAlgorithm.DIR.equals(jweKeyAlgorithm)) {
            if (jwk instanceof OctetSequenceKey octetSequenceKey) {
                DirectEncrypter directEncrypter = new DirectEncrypter(octetSequenceKey);
                encryptedJWT.encrypt(directEncrypter);
            }
        } else if (JWEAlgorithm.Family.ECDH_ES.contains(jweKeyAlgorithm)) {
            if (jwk instanceof ECKey ecKey) {
                ECDHEncrypter ecdhEncrypter = new ECDHEncrypter(ecKey);
                encryptedJWT.encrypt(ecdhEncrypter);
            }
        } else {
            throw new NoSuchAlgorithmException("不支持的算法：" + keyAlgorithm);
        }

        return encryptedJWT.serialize();
    }


    @Override
    public T parseToken(String token, Class<T> clazz) throws ParseException, NoSuchAlgorithmException, JOSEException {
        JWTClaimsSet claimsFromToken = getJWT(token).getJWTClaimsSet();
        return jsonMapper.convertValue(claimsFromToken.getClaim(jwtProperties.getDataKey()), clazz);
    }

    @Override
    public String getData(String token) throws ParseException, NoSuchAlgorithmException, JOSEException {
        JWTClaimsSet claimsFromToken = getJWT(token).getJWTClaimsSet();
        return claimsFromToken.getClaimAsString(jwtProperties.getDataKey());
    }

    @Override
    public JWT getJWT(String token) throws ParseException, JOSEException, NoSuchAlgorithmException {

        EncryptedJWT encryptedJWT = EncryptedJWT.parse(token);
        JWEHeader header = encryptedJWT.getHeader();
        String keyID = header.getKeyID();
        JWEAlgorithm jweKeyAlgorithm = header.getAlgorithm();
        JWK jwk = jwkSource.get(new JWKSelector(new JWKMatcher.Builder()
                .keyID(keyID)
                .algorithm(jweKeyAlgorithm)
                .build()), null).getFirst();

        if (JWEAlgorithm.Family.AES_KW.contains(jweKeyAlgorithm) || JWEAlgorithm.Family.AES_GCM_KW.contains(jweKeyAlgorithm)) {

            if (jwk instanceof OctetSequenceKey octetSequenceKey) {
                AESDecrypter aesDecrypter = new AESDecrypter(octetSequenceKey);
                encryptedJWT.decrypt(aesDecrypter);
            }

        } else if (JWEAlgorithm.Family.RSA.contains(jweKeyAlgorithm)) {

            if (jwk instanceof RSAKey rsaKey) {
                RSADecrypter rsaDecrypter = new RSADecrypter(rsaKey);
                encryptedJWT.decrypt(rsaDecrypter);
            }

        } else if (JWEAlgorithm.DIR.equals(jweKeyAlgorithm)) {

            if (jwk instanceof OctetSequenceKey octetSequenceKey) {
                DirectDecrypter directDecrypter = new DirectDecrypter(octetSequenceKey);
                encryptedJWT.decrypt(directDecrypter);
            }

        } else if (JWEAlgorithm.Family.ECDH_ES.contains(jweKeyAlgorithm)) {

            if (jwk instanceof ECKey ecKey) {
                ECDHDecrypter ecdhDecrypter = new ECDHDecrypter(ecKey);
                encryptedJWT.decrypt(ecdhDecrypter);
            }
        } else {
            throw new NoSuchAlgorithmException("不支持的算法：" + jweKeyAlgorithm.getName());
        }


        return encryptedJWT;
    }



    @Override
    public boolean validateToken(String token) {
        try {
            getJWT(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public long getRemainingSeconds(String token) {
        try {
            JWTClaimsSet claims = getJWT(token).getJWTClaimsSet();
            Date expiration = claims.getExpirationTime();
            return (expiration.getTime() - System.currentTimeMillis()) / 1000;
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public String getTokenId(String token) {
        try {
            JWTClaimsSet claims = getJWT(token).getJWTClaimsSet();
            return claims.getClaim(CLAIM_KEY_TOKEN_ID).toString();
        } catch (Exception e) {
            return null;
        }
    }

}
