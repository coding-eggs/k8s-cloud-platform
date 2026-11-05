package com.coding.auth.internal;

import com.coding.auth.config.GlobalExceptionHandler;
import com.coding.auth.config.InternalApiSecurityConfig;
import com.coding.auth.controller.InternalClientController;
import com.coding.auth.controller.JwkController;
import com.coding.auth.service.ClientAdminService;
import com.coding.common.audit.ServletAuditConfiguration;
import com.coding.data.mapper.auth.Oauth2JwkMapper;
import com.coding.data.mapper.system.PlatformAuditLogMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.Filter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Date;
import java.util.Map;

import static org.mockito.Mockito.mock;

/**
 * auth 的 {@code /internal/**} 切片：真内部安全链 + 真审计切面 + 两个内部 controller，
 * 业务服务与 mapper 用替身；token 用本地 RSA 密钥签（auth 的 JWKSource 换成它）。
 */
@Configuration
@EnableWebMvc
@EnableWebSecurity
@EnableAspectJAutoProxy
@Import({InternalApiSecurityConfig.class, ServletAuditConfiguration.class,
        InternalClientController.class, JwkController.class, GlobalExceptionHandler.class})
public class InternalSliceConfig {

    public static final String AUDIENCE = "platform-web-client";

    static final RSAKey KEY = rsa();

    @Bean
    JWKSource<SecurityContext> jwkSource() {
        return new ImmutableJWKSet<>(new JWKSet(KEY));
    }

    @Bean
    ClientAdminService clientAdminService() {
        return mock(ClientAdminService.class);
    }

    @Bean
    Oauth2JwkMapper oauth2JwkMapper() {
        return mock(Oauth2JwkMapper.class);
    }

    @Bean
    PlatformAuditLogMapper platformAuditLogMapper() {
        return mock(PlatformAuditLogMapper.class);
    }

    @Bean
    JsonMapper jsonMapper() {
        return JsonMapper.builder().build();
    }

    public static MockMvc mockMvc(WebApplicationContext ctx) {
        return MockMvcBuilders.webAppContextSetup(ctx)
                .addFilters(ctx.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    public static String token() {
        return token(AUDIENCE);
    }

    public static String token(String audience) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject("alice")
                    .audience(audience)
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .claim("data", Map.of("username", "alice", "displayName", "Alice"))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(KEY));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey rsa() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-sig").generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
