
package com.coding.k8sserver.configs;

import com.nimbusds.jose.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.proc.*;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author Joe Grandja
 * @since 0.0.1
 */
@EnableWebSecurity
@Configuration(proxyBeanMethods = false)
public class ResourceServerConfig {


	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) {

		http
				.headers(header -> header
				.frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin)
				.cacheControl(HeadersConfigurer.CacheControlConfig::disable))
				.csrf(AbstractHttpConfigurer::disable)
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers("/callback").permitAll()
						.anyRequest().authenticated())
				.oauth2ResourceServer(ors ->
						ors.jwt(Customizer.withDefaults()));

		return http.build();
	}


	@Bean
	public JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
								 OAuth2JwtProperties oAuth2JwtProperties) {

		if (StringUtils.hasText(oAuth2JwtProperties.getType()) && "JWE".equals(oAuth2JwtProperties.getType())) {

			if (oAuth2JwtProperties.getJwe() != null) {
				String secret = oAuth2JwtProperties.getJwe().getSecret();
				String secretAlg = oAuth2JwtProperties.getJwe().getSecretAlg();
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
				String secret = oAuth2JwtProperties.getJws().getSecret();
				String secretAlg = oAuth2JwtProperties.getJws().getSecretAlg();
				//对称签名需要自己提供密钥
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
		config.addAllowedOrigin("*");
		config.setAllowCredentials(true);
		source.registerCorsConfiguration("/**", config);
		return source;
	}



}
