package com.coding.auth.config;

import com.coding.common.components.jwt.impl.JweTokenStrategy;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.AESEncrypter;
import com.nimbusds.jose.crypto.DirectEncrypter;
import com.nimbusds.jose.crypto.ECDHEncrypter;
import com.nimbusds.jose.crypto.RSAEncrypter;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.EncryptedJWT;
import com.nimbusds.jwt.JWT;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.util.Base64;
import java.util.List;

import static com.coding.auth.config.CustomClientSetting.*;

@Component
@RequiredArgsConstructor
public class CustomJweEncoder {

    // 可自行实现带缓存的远程 JWK 获取
    private final RemoteJwkSetCache remoteJwkSetCache;

    private final JWKSource<SecurityContext> jwkSource;

    private final JweTokenStrategy<Object> jweTokenStrategy;

    public JWEData encode(String signedJwt,  CustomClientSetting clientSetting) {
        //jwk url
        String jwkUrl = clientSetting.getJweJwkUrl();
        //密钥管理算法
        String keyAlgStr = clientSetting.getJweKeyAlg();
        //内容加密算法
        String encMethodStr = clientSetting.getJweEncMethod();
        //对称密钥
        String symmetricSecret = clientSetting.getJweSecret();
        //对称密钥加密的密钥id
        String jweSecretKeyId = clientSetting.getJweSecretKeyId();

        JWEAlgorithm keyAlg = StringUtils.hasText(keyAlgStr)
                ? JWEAlgorithm.parse(keyAlgStr)
                : JWEAlgorithm.RSA_OAEP_256;

        EncryptionMethod encMethod = StringUtils.hasText(encMethodStr)
                ? EncryptionMethod.parse(encMethodStr)
                : EncryptionMethod.A256GCM;

        try {
            JWEHeader.Builder jweHeaderBuilder = new JWEHeader.Builder(keyAlg, encMethod)
                    // 重要：声明内层是 JWT
                    .contentType("JWT");
            // 非对称加密（推荐）
            if (JWEAlgorithm.Family.ASYMMETRIC.contains(keyAlg)) {
                if (!StringUtils.hasText(jwkUrl)) {
                    throw new JwtEncodingException("JWE 非对称加密必须提供 jwkSetUrl");
                }

                JWKSet jwkSet = remoteJwkSetCache.getJwkSet(jwkUrl);
                List<JWK> encryptionKeys = jwkSet.getKeys().stream()
                        .filter(key -> KeyUse.ENCRYPTION.equals(key.getKeyUse())
                                || key.getKeyUse() == null)
                        .toList();

                if (encryptionKeys.isEmpty()) {
                    throw new JwtEncodingException("客户端 jwkSetUrl 中未找到用于加密的公钥");
                }
                JWK recipientPublicKey = encryptionKeys.getFirst();
                jweHeaderBuilder.keyID(recipientPublicKey.getKeyID());
                jweHeaderBuilder.jwkURL(URI.create(jwkUrl));

                JWEHeader jweHeader = jweHeaderBuilder.build();

                JWEObject jweObject = new JWEObject(jweHeader, new Payload(signedJwt));

                if (JWEAlgorithm.Family.RSA.contains(keyAlg)) {
                    jweObject.encrypt(new RSAEncrypter(recipientPublicKey.toRSAKey().toRSAPublicKey()));
                } else if (JWEAlgorithm.Family.ECDH_ES.contains(keyAlg)) {
                    // 支持 ECDH-ES，可自行扩展
                    jweObject.encrypt(new ECDHEncrypter(recipientPublicKey.toECKey()));
                } else {
                    throw new JwtEncodingException("不支持的 JWE key 算法: " + keyAlg);
                }

                return new JWEData(jweObject.serialize(), jweHeader) ;
            }
            else if (JWEAlgorithm.Family.SYMMETRIC.contains(keyAlg) && StringUtils.hasText(symmetricSecret)) {
                //对称加密
                String secret = jweTokenStrategy.getJWT(symmetricSecret,jweSecretKeyId, jwkSource);
                JWEHeader jweHeader = jweHeaderBuilder.build();
                JWEObject jweObject = new JWEObject(jweHeader, new Payload(signedJwt));
                if (JWEAlgorithm.DIR.equals(keyAlg)) {
                    jweObject.encrypt(new DirectEncrypter(secret.getBytes(StandardCharsets.UTF_8)));
                } else {
                    jweObject.encrypt(new AESEncrypter(secret.getBytes(StandardCharsets.UTF_8)));
                }
                return new JWEData(jweObject.serialize(), jweHeader);
            }
            else {
                throw new JwtEncodingException("JWE 配置不完整，无法确定加密方式");
            }

        } catch (JOSEException e) {
            throw new JwtEncodingException("JWE 加密失败", e);
        } catch (ParseException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public record JWEData(String data, JWEHeader jweHeader){}
}
