package com.coding.auth.config;





import com.coding.common.components.JwkService;
import com.coding.common.components.jwt.JwtProperties;
import com.coding.common.components.jwt.impl.JweTokenStrategy;
import com.coding.common.components.jwt.impl.JwsTokenStrategy;
import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.auth.PlatformUserMapper;
import com.coding.data.mapper.auth.PlatformUserRoleMapper;
import com.coding.data.models.auth.PlatformUser;
import com.coding.data.models.system.SecurityUser;
import com.coding.data.models.system.TokenUserInfo;
import com.coding.data.models.system.UserTenantInfo;
import com.nimbusds.jose.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import com.nimbusds.jwt.EncryptedJWT;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;


import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.jackson.SecurityJacksonModules;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.authentication.ClientSecretAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenExchangeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;


import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.text.ParseException;
import java.util.*;

import static com.coding.auth.config.CustomClientSetting.*;

@ConditionalOnBean(JwtProperties.class)
@Configuration
public class AuthorizationServerConfig {

    private static final String CUSTOM_CONSENT_PAGE_URI = "/oauth2/consent";


    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private JwsTokenStrategy<SecurityUser> jwsTokenStrategy;

    @Autowired
    private JweTokenStrategy<Object> jweTokenStrategy;


    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain authorizationServerSecurityFilterChain(
            HttpSecurity http) {
        OAuth2AuthorizationServerConfigurer configurer = new OAuth2AuthorizationServerConfigurer();
        http
                .securityMatcher(configurer.getEndpointsMatcher())
                .with(configurer, (authorizationServer) ->
                        authorizationServer
                                //授权页面端点
                                .authorizationEndpoint(authorizationEndpoint ->
                                        authorizationEndpoint.consentPage(CUSTOM_CONSENT_PAGE_URI)
                                )
                                //oidc配置
                                .oidc(Customizer.withDefaults())
                )
                //对请求进行拦截
                .authorizeHttpRequests(authorizeHttpRequests ->
                        authorizeHttpRequests.anyRequest().authenticated())
                //异常处理
                .exceptionHandling(exceptions ->
                        exceptions.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login.html"),
                                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)
                ))

                //跨域配置
                .cors(Customizer.withDefaults());

        // @formatter:on
        return http.build();
    }

    @Bean
    @Primary
    public RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbcTemplate) {
        JdbcRegisteredClientRepository registeredClientRepository = new JdbcRegisteredClientRepository(jdbcTemplate);
        return registeredClientRepository;
    }


    // 创建一个支持你自定义类的 JsonMapper
    private JsonMapper createCustomJsonMapper() {
        ClassLoader classLoader = getClass().getClassLoader();

        // 创建 validator builder
        BasicPolymorphicTypeValidator.Builder validatorBuilder = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.coding.data.models.system.")
                .allowIfSubType("com.coding.auth.config.CustomClientSetting")
                ;

        // 关键：把 validator 传给 SecurityJacksonModules
        List<JacksonModule> modules = SecurityJacksonModules.getModules(classLoader, validatorBuilder);

        return JsonMapper.builder()
                .addModules(modules)
                .build();
    }

    /**
     * 认证持久化
     */
    @Bean
    public OAuth2AuthorizationService authorizationService(JdbcTemplate jdbcTemplate, RegisteredClientRepository registeredClientRepository ) throws Exception {
        JdbcOAuth2AuthorizationService oAuth2AuthorizationService = new JdbcOAuth2AuthorizationService(jdbcTemplate, registeredClientRepository);
        oAuth2AuthorizationService.setAuthorizationRowMapper(new JdbcOAuth2AuthorizationService.JsonMapperOAuth2AuthorizationRowMapper(registeredClientRepository, createCustomJsonMapper()));
        oAuth2AuthorizationService.setAuthorizationParametersMapper(new JdbcOAuth2AuthorizationService.JsonMapperOAuth2AuthorizationParametersMapper(createCustomJsonMapper()));

        return oAuth2AuthorizationService;
    }



    /**
     * 授权持久化
     */
    @Bean
    public OAuth2AuthorizationConsentService authorizationConsentService(JdbcTemplate jdbcTemplate, RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationConsentService(jdbcTemplate, registeredClientRepository);
    }


    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     1. JwtGenerator 构建初始 claims（issuer, sub, aud, iat, exp...）
     2. jwtCustomizer.customize(jwtEncodingContext)      ← 你改 claims/header
     3. JwsHeader header = jwsHeaderBuilder.build()       ← 固化 header
     4. JwtClaimsSet claims = claimsBuilder.build()        ← 固化 claims（已包含你的自定义内容）
     5. this.jwtEncoder.encode(JwtEncoderParameters.from(header, claims))  ← 签名+编码
     */
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenExchangeCustomizer(PlatformUserMapper userMapper, PlatformTenantMapper tenantMapper, PlatformUserRoleMapper userRoleMapper) {

        return context -> {
            //通过username 查询用户信息
            User securityUser = (User) context.getPrincipal().getPrincipal();
            String username = securityUser.getUsername();
            PlatformUser platformUser = userMapper.selectByUsername(username);

            TokenUserInfo tokenUserInfo = TokenUserInfo.builder()
                    .username(platformUser.getUsername())
                    .email(platformUser.getEmail())
                    .displayName(platformUser.getDisplayName())
                    .status(platformUser.getStatus())
                    .build();

            //平台域角色（所有 grant 类型都写入，管理端据此校验 PLATFORM:admin）
            tokenUserInfo.setPlatformRoles(userRoleMapper.selectRoleCodesByUser(platformUser.getId()));

            //仅对 token exchange grant 生效
            if (context.getAuthorizationGrantType().equals(AuthorizationGrantType.TOKEN_EXCHANGE)) {
                OAuth2TokenExchangeAuthenticationToken exchangeToken =
                        context.getAuthorizationGrant();
                if (exchangeToken != null) {
                    //从请求参数中提取租户信息
                    Map<String, Object> additionalParameters = exchangeToken.getAdditionalParameters();
                    //租户id
                    String tenantId = (String) additionalParameters.get("tenant_id");
                    //查询租户信息
                    UserTenantInfo userTenantInfo = tenantMapper.selectTenantByUser(username, tenantId);
                    if (userTenantInfo != null) {
                        tokenUserInfo.setTenantInfo(userTenantInfo);
                        //添加租户信息
                    }
                }
            }
            context.getClaims().claim(jwtProperties.getDataKey(), tokenUserInfo);
            ClientSettings clientSettings = context.getRegisteredClient().getClientSettings();

            //如果客户端需要jwe加密，则需要客户端自己提供公钥/secret
            setCustomClientSettings(context, clientSettings);
        };

    }

    public void setCustomClientSettings (JwtEncodingContext context, ClientSettings clientSettings) {

        context.getClaims().claim(CUSTOM_SETTING_KEY, CustomClientSetting.builder()
                        .jwtType(clientSettings.getSetting(CLIENT_SETTING_JWT_TYPE_KEY))
                        .jwsSigAlg(clientSettings.getSetting(CLIENT_SETTING_JWS_SIG_ALG))
                        .jwsSecret(clientSettings.getSetting(CLIENT_SETTING_JWS_SECRET_KEY))
                        .jwsSecretKeyId(clientSettings.getSetting(CLIENT_SETTING_JWS_SECRET_KID_KEY))
                        .jweEncMethod(clientSettings.getSetting(CLIENT_SETTING_JWE_ENC_METHOD_KEY))
                        .jweJwkUrl(context.getRegisteredClient().getClientSettings().getJwkSetUrl())
                        .jweSecret(clientSettings.getSetting(CLIENT_SETTING_JWE_SECRET_KEY))
                        .jweSecretKeyId(clientSettings.getSetting(CLIENT_SETTING_JWE_SECRET_KID_KEY))
                        .jweKeyAlg(clientSettings.getSetting(CLIENT_SETTING_JWE_KEY_ALG_KEY))
                .build());
    }

    //不自定义 jwe 不需要自定义JwtEncoder
    @Bean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource, CustomJweEncoder jweEncoder) {
        return parameters -> {

            Assert.notNull(parameters, "parameters cannot be null");

            if (parameters.getJwsHeader() == null) {
                throw new JwtEncodingException("jws header 不能为空");
            }

            JwtClaimsSet claims = parameters.getClaims();
            CustomClientSetting customClientSetting = claims.getClaim(CUSTOM_SETTING_KEY);

            //构建新的 jwt 补充自定义数据
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
            for (Map.Entry<String, Object> e : claims.getClaims().entrySet()) {
                //不对透传customSetting 放入claim
                if (!CUSTOM_SETTING_KEY.equals(e.getKey()))
                    builder.claim(e.getKey(), e.getValue());
            }
            builder.issueTime(Date.from(claims.getIssuedAt()));
            builder.notBeforeTime(Date.from(claims.getIssuedAt()));
            builder.expirationTime(Date.from(claims.getExpiresAt()));

            JWTClaimsSet jwtClaimsSet = builder.build();

            //先进行jws签名
            try {
                String finalToken;
                JWK jwk;
                //对称算法签名
                if (JWSAlgorithm.Family.HMAC_SHA.contains(JWSAlgorithm.parse(customClientSetting.getJwsSigAlg())) &&
                        StringUtils.hasText(customClientSetting.getJwsSecret())) {
                    //对称算法的密钥（对称密钥是使用非对称加密的方式存储在数据库的，所以下面需要解密）
                    String jwsSecret = customClientSetting.getJwsSecret();

                    //明文密钥
                    String realSecret =  jweTokenStrategy.getData(jwsSecret);

                    //对称密钥jwk
                    jwk = new OctetSequenceKey.Builder(realSecret.getBytes(StandardCharsets.UTF_8))
                            .algorithm(JWSAlgorithm.parse(customClientSetting.getJwsSigAlg()))
                            .keyUse(KeyUse.SIGNATURE)
                            .build();
                } else {
                    //非对称签名算法 匹配只用来签名的
                    JWKMatcher jwkMatcher = new JWKMatcher.Builder()
                            .algorithm(JWSAlgorithm.parse(customClientSetting.getJwsSigAlg()))
                            .keyUse(KeyUse.SIGNATURE)
                            .build();
                    //获取 server 配置的 JWK
                    List<JWK> jwkList = jwkSource.get(new JWKSelector(jwkMatcher), null);
                    if (CollectionUtils.isEmpty(jwkList)) {
                        throw new JwtEncodingException("未找到匹配的JWK");
                    }
                    //非对称密钥jwk
                    jwk = jwkList.getFirst();
                }
                //签名
                finalToken = jwsTokenStrategy.sign(jwtClaimsSet, jwk);

                String jwtType = customClientSetting.getJwtType();

                Map<String, Object> headers;
                //如果客户端需要JWE方式进行加密
                if (StringUtils.hasText(jwtType) && jwtType.equals("JWE")) {
                    CustomJweEncoder.JWEData jweData = jweEncoder.encode(finalToken, customClientSetting);
                    finalToken = jweData.data();
                    headers = jweData.jweHeader().toJSONObject();
                } else {
                    //构建新的header, 不进行jwe加密，keyID就是自己库内的keyID
                    headers = new JWSHeader.Builder(JWSAlgorithm.parse(jwk.getAlgorithm().getName()))
                            .keyID(jwk.getKeyID())
                            .type(JOSEObjectType.JWT)
                            .build()
                            .toJSONObject();
                }

                return new Jwt(finalToken, claims.getIssuedAt(), claims.getExpiresAt(),
                        headers, claims.getClaims());

            } catch (NoSuchAlgorithmException | InvalidKeySpecException | JOSEException e) {
                throw new JwtEncodingException("jwt 签名失败");
            } catch (ParseException e) {
                throw new RuntimeException(e);
            }
        };
    }




    @Bean
    public ClientSecretAuthenticationProvider clientSecretAuthenticationProvider(RegisteredClientRepository registeredClientRepository ,
                                                                                 OAuth2AuthorizationService authorizationService ,
                                                                                 PasswordEncoder passwordEncoder) {
        ClientSecretAuthenticationProvider clientSecretAuthenticationProvider =
                new ClientSecretAuthenticationProvider(registeredClientRepository, authorizationService);

        clientSecretAuthenticationProvider.setPasswordEncoder(passwordEncoder);
        return clientSecretAuthenticationProvider;
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder()
                .issuer(jwtProperties.getIssuer())
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder () {
        return new BCryptPasswordEncoder();
    }


}
