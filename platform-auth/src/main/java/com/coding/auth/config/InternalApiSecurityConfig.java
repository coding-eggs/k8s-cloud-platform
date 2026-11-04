package com.coding.auth.config;

import com.coding.common.components.jwt.JwtPermissionResolver;
import com.coding.common.components.jwt.PlatformJwtAuthenticationConverter;
import com.coding.common.components.jwt.PlatformJwtValidators;
import com.coding.common.exception.EnumResponseType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;

/**
 * 内部接口链（{@code /internal/**}：客户端管理、JWK 生成），挂在平台网关后面（网关 spec §7）。
 *
 * <ul>
 *   <li><b>认证</b>：网关透传的用户 JWT，用 auth 自己的 {@link JWKSource} 验签（签发方就是自己，不走 jwks 网络往返），
 *       aud 白名单与 platform-api 同一份值。原先的共享密钥头（{@code X-Platform-Internal-Token}）已删。</li>
 *   <li><b>授权</b>：只要求已认证，<b>不判权限码</b> —— 接口级权限由网关按权限表判（service=platform-auth 的行）；
 *       防绕过靠网络隔离（auth 的 /internal/** 只对网关可达，nginx 对 /auth/internal/ 回 404）。</li>
 *   <li>无会话、不走表单登录（未带凭据不会被 302 到 login.html）。排在默认链之前、授权服务器链之后。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class InternalApiSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain internalSecurityFilterChain(HttpSecurity http,
                                                           JWKSource<SecurityContext> jwkSource,
                                                           @Value("${platform.internal.jwt.audiences:}") String audiences,
                                                           @Value("${jwt.data-key:data}") String dataKey) throws Exception {
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor(jwkSource));
        // 为空拒启（withAudiences 内部判），不静默退化成「任何客户端签的 token 都收」
        decoder.setJwtValidator(PlatformJwtValidators.withAudiences(
                Arrays.stream(audiences.split(",")).filter(StringUtils::hasText).map(String::trim).toList()));

        http.securityMatcher("/internal/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint((request, response, ex) -> unauthorized(response)))
                .oauth2ResourceServer(o -> o
                        .authenticationEntryPoint((request, response, ex) -> unauthorized(response))
                        // 只要身份（审计记 actor），不需要权限闭包
                        .jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(
                                new PlatformJwtAuthenticationConverter(dataKey, JwtPermissionResolver.noop()))));
        return http.build();
    }

    /** 与授权服务器自己的解码器同一套算法族（RS/ES/PS），密钥取自 auth 的 JWKSource */
    private static DefaultJWTProcessor<SecurityContext> processor(JWKSource<SecurityContext> jwkSource) {
        Set<JWSAlgorithm> algs = Set.of(JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512,
                JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512,
                JWSAlgorithm.PS256, JWSAlgorithm.PS384, JWSAlgorithm.PS512);
        DefaultJWTProcessor<SecurityContext> p = new DefaultJWTProcessor<>();
        p.setJWSKeySelector(new JWSVerificationKeySelector<>(algs, jwkSource));
        // exp/nbf/aud 由 Spring 的 validator 统一判，这里不重复
        p.setJWTClaimsSetVerifier((claims, context) -> {
        });
        return p;
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + EnumResponseType.USER_UN_LOGIN.getCode()
                + ",\"msg\":\"" + EnumResponseType.USER_UN_LOGIN.getMsg() + "\",\"data\":null}");
    }
}
