package com.coding.gateway.security;

import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.AESEncrypter;
import com.nimbusds.jose.crypto.DirectEncrypter;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 网关解码器与 platform-api / k8s-server 的 ResourceServerConfig 三条分支一一对应：
 * 对称 JWE（内层 JWS 仍按 jwk-set-uri 验签）、对称 JWS（HMAC）、非对称 JWS（jwk-set-uri）。
 */
class GatewayJwtDecodersTest {

    private static final String AUD = "platform-web-client";
    /** 32 字节：HS256 与 dir+A256GCM / A256KW 都要求 256 位 */
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String OTHER_SECRET = "fedcba9876543210fedcba9876543210";

    private static RSAKey signingKey;
    private static HttpServer jwks;
    private static String jwkSetUri;

    @BeforeAll
    static void startJwks() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("k1").generate();
        byte[] body = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwks.createContext("/jwks", ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        jwks.start();
        jwkSetUri = "http://127.0.0.1:" + jwks.getAddress().getPort() + "/jwks";
    }

    @AfterAll
    static void stopJwks() {
        jwks.stop(0);
    }

    // ---------- 非对称 JWS（当前部署） ----------

    @Test
    void jwkSetBranchVerifiesRsaSignedToken() {
        Jwt jwt = decode(props("JWS", null, null), rsaSigned(AUD));
        assertThat(jwt.getSubject()).isEqualTo("alice");
    }

    @Test
    void missingTypeFallsBackToJwkSetBranch() {
        assertThat(decode(props(null, null, null), rsaSigned(AUD)).getSubject()).isEqualTo("alice");
    }

    // ---------- 对称 JWS（HMAC） ----------

    @Test
    void hmacBranchVerifiesTokenSignedWithSharedSecret() {
        Jwt jwt = decode(props("JWS", secret(SECRET, "HS256", null), null), hmacSigned(SECRET, AUD));
        assertThat(jwt.getSubject()).isEqualTo("alice");
    }

    @Test
    void hmacBranchRejectsTokenSignedWithOtherSecret() {
        ReactiveJwtDecoder d = GatewayJwtDecoders.build(jwkSetUri, props("JWS", secret(SECRET, "HS256", null), null));
        assertThatThrownBy(() -> d.decode(hmacSigned(OTHER_SECRET, AUD)).block())
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    void hmacBranchRejectsRsaSignedToken() {
        ReactiveJwtDecoder d = GatewayJwtDecoders.build(jwkSetUri, props("JWS", secret(SECRET, "HS256", null), null));
        assertThatThrownBy(() -> d.decode(rsaSigned(AUD)).block()).isInstanceOf(BadJwtException.class);
    }

    // ---------- 对称 JWE（内层 JWS 按 jwk-set-uri 验签） ----------

    @Test
    void jweDirBranchDecryptsAndVerifiesNestedToken() {
        Jwt jwt = decode(props("JWE", null, secret(SECRET, "dir", "A256GCM")),
                encrypted(rsaSigned(AUD), JWEAlgorithm.DIR, SECRET));
        assertThat(jwt.getSubject()).isEqualTo("alice");
    }

    @Test
    void jweKeyWrapBranchDecryptsAndVerifiesNestedToken() {
        Jwt jwt = decode(props("JWE", null, secret(SECRET, "A256KW", "A256GCM")),
                encrypted(rsaSigned(AUD), JWEAlgorithm.A256KW, SECRET));
        assertThat(jwt.getSubject()).isEqualTo("alice");
    }

    @Test
    void jweBranchRejectsTokenEncryptedWithOtherSecret() {
        ReactiveJwtDecoder d = GatewayJwtDecoders.build(jwkSetUri, props("JWE", null, secret(SECRET, "dir", "A256GCM")));
        assertThatThrownBy(() -> d.decode(encrypted(rsaSigned(AUD), JWEAlgorithm.DIR, OTHER_SECRET)).block())
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    void jweBranchRejectsNestedTokenNotSignedByAuthServer() throws Exception {
        RSAKey stranger = new RSAKeyGenerator(2048).keyID("k1").generate();
        ReactiveJwtDecoder d = GatewayJwtDecoders.build(jwkSetUri, props("JWE", null, secret(SECRET, "dir", "A256GCM")));
        assertThatThrownBy(() -> d.decode(encrypted(rsaSigned(stranger, AUD), JWEAlgorithm.DIR, SECRET)).block())
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    void jweWithoutSecretRejectsEveryToken() {
        ReactiveJwtDecoder d = GatewayJwtDecoders.build(jwkSetUri, props("JWE", null, null));
        assertThatThrownBy(() -> d.decode(rsaSigned(AUD)).block()).isInstanceOf(BadJwtException.class);
    }

    // ---------- aud 白名单在每条分支都生效 ----------

    @Test
    void audienceWhitelistAppliesToEveryBranch() {
        assertThatThrownBy(() -> decode(props("JWS", null, null), rsaSigned("other-client")))
                .isInstanceOf(JwtValidationException.class);
        assertThatThrownBy(() -> decode(props("JWS", secret(SECRET, "HS256", null), null), hmacSigned(SECRET, "other-client")))
                .isInstanceOf(JwtValidationException.class);
        assertThatThrownBy(() -> decode(props("JWE", null, secret(SECRET, "dir", "A256GCM")),
                encrypted(rsaSigned("other-client"), JWEAlgorithm.DIR, SECRET)))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void emptyAudiencesRefusesToBuild() {
        OAuth2JwtProperties p = props("JWS", null, null);
        p.setAudiences(List.of());
        assertThatThrownBy(() -> GatewayJwtDecoders.build(jwkSetUri, p)).isInstanceOf(IllegalStateException.class);
    }

    // ---------- helpers ----------

    private static Jwt decode(OAuth2JwtProperties p, String token) {
        return GatewayJwtDecoders.build(jwkSetUri, p).decode(token).block();
    }

    private static OAuth2JwtProperties props(String type, OAuth2JwtProperties.Secret jws, OAuth2JwtProperties.Secret jwe) {
        OAuth2JwtProperties p = new OAuth2JwtProperties();
        p.setType(type);
        p.setAudiences(List.of(AUD));
        p.setJws(jws);
        p.setJwe(jwe);
        return p;
    }

    private static OAuth2JwtProperties.Secret secret(String value, String alg, String enc) {
        OAuth2JwtProperties.Secret s = new OAuth2JwtProperties.Secret();
        s.setSecret(value);
        s.setSecretAlg(alg);
        s.setEncMethod(enc);
        return s;
    }

    private static JWTClaimsSet claims(String aud) {
        return new JWTClaimsSet.Builder()
                .subject("alice")
                .audience(aud)
                .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .build();
    }

    private static String rsaSigned(String aud) {
        return rsaSigned(signingKey, aud);
    }

    private static String rsaSigned(RSAKey key, String aud) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims(aud));
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hmacSigned(String secret, String aud) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims(aud));
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 与 platform-auth 的 CustomJweEncoder 同形：内层是已签名 JWT，cty=JWT */
    private static String encrypted(String signedJwt, JWEAlgorithm alg, String secret) {
        try {
            JWEObject jwe = new JWEObject(new JWEHeader.Builder(alg, EncryptionMethod.A256GCM).contentType("JWT").build(),
                    new Payload(SignedJWT.parse(signedJwt)));
            byte[] key = secret.getBytes(StandardCharsets.UTF_8);
            jwe.encrypt(JWEAlgorithm.DIR.equals(alg) ? new DirectEncrypter(key) : new AESEncrypter(key));
            return jwe.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
