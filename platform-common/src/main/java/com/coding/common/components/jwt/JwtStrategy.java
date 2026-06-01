package com.coding.common.components.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jwt.JWT;

import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.text.ParseException;

/**
 * jwt 策略接口
 */
public interface JwtStrategy<T> {


    String generateToken(T t, String tokenType, long expirationSeconds, String subject) throws NoSuchAlgorithmException, ParseException, InvalidKeySpecException, JOSEException;

    /**
     * 验证并解析令牌
     */
    T parseToken(String token, Class<T> clazz) throws  ParseException, JOSEException, NoSuchAlgorithmException, InvalidKeySpecException;

    JWT getJWT(String token, JWK jwk) throws ParseException, JOSEException, NoSuchAlgorithmException, InvalidKeySpecException;


    JWK getJWK() throws ParseException, JOSEException;

    /**
     * 验证令牌是否有效
     */
    boolean validateToken(String token);

    /**
     * 获取令牌剩余有效期（秒）
     */
    long getRemainingSeconds(String token);

    /**
     * 获取令牌ID（用于防重放）
     */
    String getTokenId(String token);

}
