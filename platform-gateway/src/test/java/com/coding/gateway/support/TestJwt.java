package com.coding.gateway.support;

import com.coding.common.components.jwt.PlatformJwtValidators;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** 本地 RSA 密钥签 token；{@link LocalKeys} 换掉 jwk-set-uri 解码器（同一份 aud 白名单）。 */
public final class TestJwt {

    public static final String AUDIENCE = "platform-web-client";

    private static final KeyPair KEYS = rsa();

    private TestJwt() {
    }

    @TestConfiguration
    public static class LocalKeys {
        @Bean
        @Primary
        ReactiveJwtDecoder localDecoder() {
            NimbusReactiveJwtDecoder d = NimbusReactiveJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
            d.setJwtValidator(PlatformJwtValidators.withAudiences(List.of(AUDIENCE)));
            return d;
        }
    }

    public static String token() {
        return token(AUDIENCE);
    }

    public static String token(String audience) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject("alice")
                    .audience(audience)
                    .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                    .claim("data", Map.of("username", "alice"))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner(KEYS.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
