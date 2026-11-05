package com.coding.gateway.security;

import com.coding.common.components.permission.PlatformServices;
import com.coding.common.components.cache.RedisJsonCache;
import com.coding.common.components.jwt.CachedPermissionClosureResolver;
import com.coding.common.components.jwt.JwtPermissionResolver;
import com.coding.common.components.jwt.PermissionClosureService;
import com.coding.common.components.jwt.PlatformJwtAuthenticationConverter;
import com.coding.common.components.permission.PermissionDecider;
import com.coding.common.components.permission.PermissionRegistry;
import com.coding.common.exception.EnumResponseType;
import com.coding.data.mapper.auth.PlatformPermissionMapper;
import com.coding.data.mapper.auth.PlatformUserMapper;
import com.coding.data.mapper.auth.PlatformUserRoleMapper;
import com.coding.data.mapper.auth.PlatformUserTenantRoleMapper;
import com.coding.data.mapper.system.PlatformAuditLogMapper;
import com.coding.gateway.audit.GatewayDeniedAuditor;
import com.coding.gateway.route.PlatformRouteProperties;
import com.coding.gateway.route.PlatformRouteResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.util.AntPathMatcher;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

/**
 * 网关安全链（spec §8）：JWT 校验 + 表驱动接口鉴权。数据范围（租户/分配表）仍在各业务服务。
 *
 * <p>无会话、无 CSRF、不配 CORS（浏览器经 nginx 同源访问）。响应头交给上游写：网关再写一遍会重复。
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
@EnableConfigurationProperties(OAuth2JwtProperties.class)
public class GatewaySecurityConfig {

    @Bean
    public PermissionDecider permissionDecider(PermissionRegistry registry) {
        return new PermissionDecider(registry);
    }

    @Bean
    public GatewayAuthorizationManager gatewayAuthorizationManager(PlatformRouteResolver routes, PermissionDecider decider) {
        return new GatewayAuthorizationManager(routes, decider);
    }

    /** 与 platform-api 同一实现、同一份 Redis 键（契约见 CachedPermissionClosureResolver） */
    @Bean
    public PermissionClosureService permissionClosureService(PlatformUserMapper userMapper,
                                                             PlatformUserRoleMapper userRoleMapper,
                                                             PlatformUserTenantRoleMapper userTenantRoleMapper,
                                                             PlatformPermissionMapper permissionMapper) {
        return new PermissionClosureService(userMapper, userRoleMapper, userTenantRoleMapper, permissionMapper);
    }

    @Bean
    public RedisJsonCache redisJsonCache(StringRedisTemplate redis, JsonMapper jsonMapper) {
        return new RedisJsonCache(redis, jsonMapper);
    }

    @Bean
    public JwtPermissionResolver permissionClosureResolver(PermissionClosureService closureService,
                                                           RedisJsonCache cache,
                                                           @Value("${platform.permissions.cache-ttl-seconds:30}") long ttlSeconds) {
        return new CachedPermissionClosureResolver(closureService, cache, Duration.ofSeconds(ttlSeconds));
    }

    @Bean
    public GatewayDeniedAuditor gatewayDeniedAuditor(PlatformAuditLogMapper mapper,
                                                     @Value("${jwt.data-key:data}") String dataKey) {
        return new GatewayDeniedAuditor(mapper, dataKey);
    }

    /** 三种 token（对称 JWE / HMAC JWS / jwk-set-uri JWS）与 platform-api 一一对应，见 {@link GatewayJwtDecoders}；aud 白名单为空拒启。 */
    @Bean
    public ReactiveJwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
                                         OAuth2JwtProperties jwtProperties) {
        return GatewayJwtDecoders.build(jwkSetUri, jwtProperties);
    }

    @Bean
    public SecurityWebFilterChain gatewaySecurityChain(ServerHttpSecurity http,
                                                       PlatformRouteProperties props,
                                                       PlatformRouteResolver routes,
                                                       GatewayAuthorizationManager authorizationManager,
                                                       ReactiveJwtDecoder jwtDecoder,
                                                       JwtPermissionResolver permissionResolver,
                                                       GatewayDeniedAuditor auditor,
                                                       @Value("${jwt.data-key:data}") String dataKey) {
        ServerWebExchangeMatcher publicPaths = publicPaths(routes, props.publicPaths());
        ServerAuthenticationEntryPoint unauthorized = (exchange, e) ->
                AuthJsonResponses.write(exchange, HttpStatus.UNAUTHORIZED, EnumResponseType.USER_UN_LOGIN);
        // 403 = 已认证但无权：先审计（永不出错）再写响应体
        ServerAccessDeniedHandler forbidden = (exchange, e) -> exchange.getPrincipal()
                .ofType(Authentication.class)
                .flatMap(a -> auditor.record(exchange, a))
                .then(Mono.defer(() -> AuthJsonResponses.write(
                        exchange, HttpStatus.FORBIDDEN, EnumResponseType.NON_AUTH_ENTRY_POINT)));

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .headers(ServerHttpSecurity.HeaderSpec::disable)
                .requestCache(c -> c.requestCache(NoOpServerRequestCache.getInstance()))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(a -> a
                        // 网关自己的健康检查（不转发、不在 /api 下）
                        .pathMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .matchers(publicPaths).permitAll()
                        .anyExchange().access(authorizationManager))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .oauth2ResourceServer(o -> o
                        .bearerTokenConverter(new PlatformBearerTokenConverter(publicPaths, routes))
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden)
                        .jwt(j -> j
                                .jwtDecoder(jwtDecoder)
                                .jwtAuthenticationConverter(new BlockingJwtAuthenticationConverter(
                                        new PlatformJwtAuthenticationConverter(dataKey, permissionResolver)))))
                .build();
    }

    /** 公开路径只对 platform-api 的路由生效（auth 的 /internal/** 一律要登录） */
    private static ServerWebExchangeMatcher publicPaths(PlatformRouteResolver routes, List<String> patterns) {
        AntPathMatcher ant = new AntPathMatcher();
        return exchange -> routes.resolve(exchange.getRequest().getPath().value())
                .filter(r -> PlatformServices.API.equals(r.route().service()))
                .filter(r -> patterns.stream().anyMatch(p -> ant.match(p, r.internalPath())))
                .map(r -> ServerWebExchangeMatcher.MatchResult.match())
                .orElseGet(ServerWebExchangeMatcher.MatchResult::notMatch);
    }
}
