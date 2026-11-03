package com.coding.auth.service;

import com.coding.common.components.jwt.JwtProperties;
import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.crypto.ECDHEncrypter;
import com.nimbusds.jose.crypto.RSAEncrypter;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.EncryptedJWT;
import com.nimbusds.jwt.JWTClaimsSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把客户端的对称密钥加密后落库。
 *
 * <p>格式与旧管理页一致（用 auth 自己的加密公钥做 JWE，明文放在 {@code data} claim），
 * 签发 token 时由 {@code JweTokenStrategy.getData} 解出 —— 新旧行走同一条解密路径。
 * 与旧页面的区别只是加密从浏览器挪到了服务端（内网 http 下浏览器没有 WebCrypto）。
 */
@Component
@RequiredArgsConstructor
public class ClientSecretSealer {

    private final JWKSource<SecurityContext> jwkSource;

    private final JwtProperties jwtProperties;

    public String seal(String plain) {
        try {
            List<JWK> keys = jwkSource.get(new JWKSelector(new JWKMatcher.Builder()
                    .keyUse(KeyUse.ENCRYPTION)
                    .build()), null);
            if (keys == null || keys.isEmpty()) {
                throw new IllegalStateException("auth 未配置加密用 JWK，无法保存对称密钥");
            }
            JWK jwk = keys.getFirst();
            JWEHeader header = new JWEHeader.Builder(JWEAlgorithm.parse(jwk.getAlgorithm().getName()),
                    EncryptionMethod.parse(jwtProperties.getEncryption().getContentAlgorithm()))
                    //解密端按 kid 找私钥，密钥轮换后也能定位
                    .keyID(jwk.getKeyID())
                    .build();
            EncryptedJWT jwt = new EncryptedJWT(header, new JWTClaimsSet.Builder()
                    .claim(jwtProperties.getDataKey(), plain)
                    .build());
            if (jwk instanceof RSAKey rsa) {
                jwt.encrypt(new RSAEncrypter(rsa));
            } else if (jwk instanceof ECKey ec) {
                jwt.encrypt(new ECDHEncrypter(ec));
            } else {
                throw new IllegalStateException("不支持的加密 JWK 类型: " + jwk.getKeyType());
            }
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("对称密钥加密失败", e);
        }
    }
}
