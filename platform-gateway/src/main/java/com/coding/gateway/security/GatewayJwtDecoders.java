package com.coding.gateway.security;

import com.coding.common.components.jwt.PlatformJwtValidators;
import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWEDecryptionKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 网关的 JWT 解码器：与 platform-api / k8s-server 的 {@code ResourceServerConfig#buildDecoder} 三条分支一一对应
 * （网关透传同一个 token，三处必须解得开同一种 token）：
 * <ol>
 *   <li>{@code type: JWE} + {@code jwe} 密钥 → 先用对称密钥解密，内层 JWS 再按 jwk-set-uri 验签；</li>
 *   <li>{@code jws} 密钥 → 对称签名（HMAC）；</li>
 *   <li>都不配 → 按 jwk-set-uri 验非对称签名（当前部署）。</li>
 * </ol>
 * {@code type: JWE} 而没配 {@code jwe} 密钥（非对称 JWE）：与 api 一样尚未实现，一律拒绝 —— 但启动时打 WARN，不静默。
 *
 * <p>为什么 JWE 分支不用 {@code NimbusReactiveJwtDecoder.withJwkSetUri(..).jwtProcessorCustomizer(..)}：
 * 那个响应式 builder 在取密钥前把 token 头强转成 {@code JWSHeader}，JWE 的头是 {@code JWEHeader} → 直接抛
 * ClassCastException。所以 JWE 分支自己装 Nimbus 处理器（拉 JWK 是阻塞的，挪到 boundedElastic；Nimbus 自带缓存）。
 *
 * <p>aud 白名单在每条分支统一装上，为空拒启（{@link PlatformJwtValidators}）。
 */
@Slf4j
public final class GatewayJwtDecoders {

    static final List<SignatureAlgorithm> ASYMMETRIC_ALGORITHMS = List.of(
            SignatureAlgorithm.RS256, SignatureAlgorithm.RS384, SignatureAlgorithm.RS512,
            SignatureAlgorithm.ES256, SignatureAlgorithm.ES384, SignatureAlgorithm.ES512);

    private GatewayJwtDecoders() {
    }

    public static ReactiveJwtDecoder build(String jwkSetUri, OAuth2JwtProperties props) {
        NimbusReactiveJwtDecoder decoder = raw(jwkSetUri, props);
        decoder.setJwtValidator(PlatformJwtValidators.withAudiences(props.getAudiences()));
        return decoder;
    }

    private static NimbusReactiveJwtDecoder raw(String jwkSetUri, OAuth2JwtProperties props) {
        if ("JWE".equals(props.getType())) {
            if (props.getJwe() != null) {
                return symmetricJwe(jwkSetUri, props.getJwe());
            }
            log.warn("type=JWE 但未配置 jwe 密钥：非对称 JWE 尚未实现（与 platform-api 一致），网关将拒绝所有 token");
            return new NimbusReactiveJwtDecoder(jwt -> Mono.error(
                    new BadJwtException("非对称 JWE 尚未实现：请配置 spring.security.oauth2.resourceserver.jwt.jwe")));
        }
        if (props.getJws() != null) {
            String alg = props.getJws().getSecretAlg();
            SecretKey key = new SecretKeySpec(props.getJws().getSecret().getBytes(StandardCharsets.UTF_8), hmacJcaName(alg));
            return NimbusReactiveJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.from(alg)).build();
        }
        return NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithms(a -> a.addAll(ASYMMETRIC_ALGORITHMS))
                .build();
    }

    private static NimbusReactiveJwtDecoder symmetricJwe(String jwkSetUri, OAuth2JwtProperties.Secret jwe) {
        JWEAlgorithm alg = JWEAlgorithm.parse(jwe.getSecretAlg());
        EncryptionMethod enc = EncryptionMethod.parse(jwe.getEncMethod());
        OctetSequenceKey key = new OctetSequenceKey.Builder(jwe.getSecret().getBytes(StandardCharsets.UTF_8))
                .keyUse(KeyUse.ENCRYPTION)
                .algorithm(alg)
                .build();

        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWEKeySelector(new JWEDecryptionKeySelector<>(alg, enc, new ImmutableJWKSet<>(new JWKSet(key))));
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(jwsAlgorithms(), remoteJwks(jwkSetUri)));
        // 与 Spring 自带构造一致：不限内层 typ；exp/nbf/aud 交给 Spring 的校验器（setJwtValidator）
        processor.setJWSTypeVerifier((header, context) -> { });
        processor.setJWTClaimsSetVerifier((claims, context) -> { });

        return new NimbusReactiveJwtDecoder(jwt -> Mono.fromCallable(() -> {
                    try {
                        return processor.process(jwt, null);
                    } catch (BadJOSEException e) {
                        throw new BadJwtException("Failed to validate the token", e);
                    } catch (JOSEException e) {
                        throw new JwtException("Failed to validate the token", e);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic()));
    }

    private static Set<JWSAlgorithm> jwsAlgorithms() {
        return ASYMMETRIC_ALGORITHMS.stream().map(a -> JWSAlgorithm.parse(a.getName())).collect(Collectors.toSet());
    }

    private static JWKSource<SecurityContext> remoteJwks(String jwkSetUri) {
        try {
            return JWKSourceBuilder.create(URI.create(jwkSetUri).toURL()).build();
        } catch (MalformedURLException e) {
            throw new IllegalStateException("jwk-set-uri 不是合法 URL: " + jwkSetUri, e);
        }
    }

    private static String hmacJcaName(String joseAlg) {
        return switch (joseAlg) {
            case "HS384" -> "HmacSHA384";
            case "HS512" -> "HmacSHA512";
            default -> "HmacSHA256";
        };
    }
}
