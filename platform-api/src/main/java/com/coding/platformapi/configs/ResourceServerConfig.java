package com.coding.platformapi.configs;

import com.coding.common.components.jwt.PlatformJwtAuthenticationConverter;
import com.coding.common.components.jwt.QueryParameterBearerTokenResolver;
import com.coding.common.exception.EnumResponseType;
import com.nimbusds.jose.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.proc.*;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * platform-api 作为资源服务器，校验 platform-auth 签发的 JWT
 */
@EnableWebSecurity
@Configuration(proxyBeanMethods = false)
public class ResourceServerConfig {

	@Data
	@Configuration
	@ConfigurationProperties(prefix = "spring.security")
	public static class Properties {
		private String[] ignoreUrls;
	}


	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, Properties properties,
										   @Value("${jwt.data-key:data}") String dataKey) {

		http
				.headers(header -> header
				.frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin)
				.cacheControl(HeadersConfigurer.CacheControlConfig::disable))
				.csrf(AbstractHttpConfigurer::disable)
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers(properties.getIgnoreUrls()).permitAll()
						//平台管理端：全部业务端点要求平台管理员（非管理员 token → 403）
						.anyRequest().hasAuthority(PlatformJwtAuthenticationConverter.PLATFORM_AUTHORITY_PREFIX + "admin"))
				//过滤器链层的认证/授权异常不会进 @ControllerAdvice，这里统一返回 ResponseData JSON
				.exceptionHandling(ex -> ex
						.authenticationEntryPoint((request, response, authException) ->
								writeAuthJson(response, 401, EnumResponseType.USER_UN_LOGIN))
						.accessDeniedHandler((request, response, accessDeniedException) ->
								writeAuthJson(response, 403, EnumResponseType.NON_AUTH_ENTRY_POINT)))
				.oauth2ResourceServer(ors -> {
					//WS 握手无法带 Authorization 头，允许 token 走 query（access_token）
					ors.bearerTokenResolver(new QueryParameterBearerTokenResolver());
					ors.jwt(jwt -> jwt.jwtAuthenticationConverter(
							new PlatformJwtAuthenticationConverter(dataKey)));
				});

		return http.build();
	}

	private void writeAuthJson(HttpServletResponse response, int status, EnumResponseType type) throws IOException {
		response.setStatus(status);
		response.setContentType("application/json;charset=UTF-8");
		response.getWriter().write(
				"{\"code\":" + type.getCode() + ",\"msg\":\"" + type.getMsg() + "\",\"data\":null}");
	}


	@Bean
	public JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
								 OAuth2JwtProperties oAuth2JwtProperties) {

		if (StringUtils.hasText(oAuth2JwtProperties.getType()) && "JWE".equals(oAuth2JwtProperties.getType())) {

			if (oAuth2JwtProperties.getJwe() != null) {
				//对称密钥
				String secret = oAuth2JwtProperties.getJwe().getSecret();
				//对称密钥加密算法
				String secretAlg = oAuth2JwtProperties.getJwe().getSecretAlg();
				//内容管理算法
				String encMethod = oAuth2JwtProperties.getJwe().getEncMethod();
				JWEAlgorithm jweAlgorithm = JWEAlgorithm.parse(secretAlg);
				//对称加密的JWE
				return NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
						.jwsAlgorithms(c -> c.addAll(getSupportAlg()))
						.jwtProcessorCustomizer(jwtProcessor -> {
							jwtProcessor.setJWEKeySelector(new JWEDecryptionKeySelector<>(jweAlgorithm, EncryptionMethod.parse(encMethod),
									//创建固定的jwkSource ，如有其他jwkSource，这里可直接更换 selector 策略
									(jwkSelector, context) -> {
										OctetSequenceKey octetSequenceKey = new OctetSequenceKey.Builder(secret.getBytes(StandardCharsets.UTF_8))
												.keyUse(KeyUse.ENCRYPTION)
												.algorithm(jweAlgorithm)
												.build();
										return Stream.of(octetSequenceKey).collect(Collectors.toUnmodifiableList());
									}));
						})
						.build();

			}else {
				//TODO 非对称加密的JWE需要给 auth server 提供 可用的jwk set uri, 解密自然也需要自己来设置jwkSources
				return new NimbusJwtDecoder(new DefaultJWTProcessor<>());
			}


		}else {
			if (oAuth2JwtProperties.getJws() != null) {
				//对称签名密钥
				String secret = oAuth2JwtProperties.getJws().getSecret();
				//对称签名密钥算法
				String secretAlg = oAuth2JwtProperties.getJws().getSecretAlg();
				SecretKey secretKey = new SecretKeySpec(
						secret.getBytes(StandardCharsets.UTF_8),
						getHMAC(secretAlg)
				);
				return NimbusJwtDecoder.withSecretKey(secretKey)
						.macAlgorithm(MacAlgorithm.from(secretAlg))
						.build();
			}else {
				//显示声明支持的算法
				return NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
						.jwsAlgorithms(c -> c.addAll(getSupportAlg()))
						.build();
			}
		}
	}

	public List<SignatureAlgorithm> getSupportAlg() {
		return List.of(SignatureAlgorithm.RS256, SignatureAlgorithm.RS384, SignatureAlgorithm.RS512,
				SignatureAlgorithm.ES256, SignatureAlgorithm.ES384, SignatureAlgorithm.ES512);
	}

	public String getHMAC(String joseAlg) {
		return switch (joseAlg) {
            case "HS384" -> "HmacSHA384";
			case "HS512" -> "HmacSHA512";
			default -> "HmacSHA256";
		};
	}


	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		CorsConfiguration config = new CorsConfiguration();
		config.addAllowedHeader("*");
		config.addAllowedMethod("*");
		// allowCredentials=true 时 allowedOrigins 不能含字面量 "*"，用 pattern 回显请求 Origin
		config.addAllowedOriginPattern("*");
		config.setAllowCredentials(true);
		source.registerCorsConfiguration("/**", config);
		return source;
	}

}
