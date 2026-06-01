package com.coding.common.components.jwt.impl;

import com.coding.common.components.JwkService;
import com.coding.common.components.jwt.JwtProperties;
import com.coding.common.components.jwt.JwtStrategy;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.text.ParseException;
import java.time.Instant;
import java.util.*;



/**
 * jws 实现jwt 内容可被读取 base64 解码即可。保证完整性和真实性，防篡改。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwsTokenStrategy<T> implements JwtStrategy<T> {

    private final JwtProperties jwtProperties;
    private final JsonMapper jsonMapper;

    private static final String CLAIM_KEY_TOKEN_ID = "jti";
    private static final String CLAIM_KEY_TYPE = "type";

    private final JwkService jwkService;



    @Override
    public String generateToken(T t, String tokenType, long expirationSeconds, String subject)
            throws NoSuchAlgorithmException, InvalidKeySpecException, JOSEException, ParseException {

        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(jwtProperties.getIssuer())
                .expirationTime(Date.from(Instant.now().plusSeconds(expirationSeconds)))
                .claim(jwtProperties.getDataKey(), t)
                .claim(CLAIM_KEY_TYPE, tokenType)
                .claim(CLAIM_KEY_TOKEN_ID, UUID.randomUUID().toString())
                .claim("iat", Instant.now().getEpochSecond())
                .build();

        return sign(claimsSet, getJWK());
    }

    public String sign(JWTClaimsSet claimsSet, JWK jwk) throws NoSuchAlgorithmException, InvalidKeySpecException, JOSEException {

        JWSAlgorithm jwsAlgorithm = JWSAlgorithm.parse(jwk.getAlgorithm().getName());
        SignedJWT signedJWT = new SignedJWT(
                new JWSHeader.Builder(jwsAlgorithm)
                        .keyID(jwk.getKeyID())
                        .build(),
                claimsSet);
        //对称加密
        if (JWSAlgorithm.Family.HMAC_SHA.contains(jwsAlgorithm)) {
            if (jwk instanceof OctetSequenceKey octetSequenceKey) {
                MACSigner macSigner = new MACSigner(octetSequenceKey);
                signedJWT.sign(macSigner);
            }

        } else if (JWSAlgorithm.Family.RSA.contains(jwsAlgorithm)) {

            if (jwk instanceof RSAKey rsaKey) {
                RSASSASigner rsassaSigner = new RSASSASigner(rsaKey);
                signedJWT.sign(rsassaSigner);
            }

        } else if (JWSAlgorithm.Family.EC.contains(jwsAlgorithm)) {

            if (jwk instanceof ECKey ecKey) {
                ECDSASigner ecdsaSigner = new ECDSASigner(ecKey);
                signedJWT.sign(ecdsaSigner);
            }

        }  else {
            throw new NoSuchAlgorithmException("不支持的算法：" + jwk.getAlgorithm());
        }

        return signedJWT.serialize();
    }



    @Override
    public T parseToken(String token, Class<T> clazz) throws ParseException, JOSEException, NoSuchAlgorithmException, InvalidKeySpecException {
        JWTClaimsSet claimsFromToken = getJWT(token, getJWK()).getJWTClaimsSet();
        return jsonMapper.convertValue(claimsFromToken.getClaim(jwtProperties.getDataKey()), clazz);
    }



    @Override
    public JWK getJWK() {
        List<JWK> oauth2Jwks = jwkService.loadAllJwkFromDb()
                .stream().filter(jwk -> jwk.getKeyUse().equals(KeyUse.SIGNATURE))
                .toList();
        return oauth2Jwks.getFirst();
    }


    @Override
    public JWT getJWT(String token, JWK jwk) throws ParseException, JOSEException, NoSuchAlgorithmException {

        String algorithm = jwk.getAlgorithm().getName();
        JWSAlgorithm jwsAlgorithm = JWSAlgorithm.parse(algorithm);
        SignedJWT signedJWT = SignedJWT.parse(token);
        //对称加密
        if (JWSAlgorithm.Family.HMAC_SHA.contains(jwsAlgorithm)) {

            if (jwk instanceof OctetSequenceKey octetSequenceKey) {
                MACVerifier verifier = new MACVerifier(octetSequenceKey);
                boolean valid = signedJWT.verify(verifier);
                if (!valid) {
                    throw new JOSEException("token 验签失败");
                }
            }

        } else if (JWSAlgorithm.Family.RSA.contains(jwsAlgorithm)) {

            if (jwk instanceof RSAKey rsaKey) {
                RSASSAVerifier verifier = new RSASSAVerifier(rsaKey);
                boolean valid = signedJWT.verify(verifier);
                if (!valid) {
                    throw new JWKException("token 验签失败");
                }
            }

        } else if (JWSAlgorithm.Family.EC.contains(jwsAlgorithm)) {

            if (jwk instanceof ECKey ecKey) {
                ECDSAVerifier ecdsaVerifier = new ECDSAVerifier(ecKey);
                boolean valid = signedJWT.verify(ecdsaVerifier);
                if (!valid) {
                    throw new JWKException("token 验签失败");
                }
            }

        } else {
            throw new NoSuchAlgorithmException("不支持的算法：" + algorithm);
        }

        return signedJWT;
    }



    @Override
    public boolean validateToken(String token) {
        try {
            getJWT(token, getJWK());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public long getRemainingSeconds(String token) {
        try {
            JWTClaimsSet claims = getJWT(token, getJWK()).getJWTClaimsSet();
            Date expiration = claims.getExpirationTime();
            return (expiration.getTime() - System.currentTimeMillis()) / 1000;
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public String getTokenId(String token) {
        try {
            JWTClaimsSet claims = getJWT(token, getJWK()).getJWTClaimsSet();
            return claims.getClaim(CLAIM_KEY_TOKEN_ID).toString();
        } catch (Exception e) {
            return null;
        }
    }


}
