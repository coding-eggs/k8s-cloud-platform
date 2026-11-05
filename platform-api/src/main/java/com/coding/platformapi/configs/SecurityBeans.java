package com.coding.platformapi.configs;

import com.coding.common.components.cache.RedisJsonCache;
import com.coding.common.components.jwt.CachedPermissionClosureResolver;
import com.coding.common.components.jwt.JwtPermissionResolver;
import com.coding.common.components.jwt.PermissionClosureService;
import com.coding.data.mapper.auth.PlatformPermissionMapper;
import com.coding.data.mapper.auth.PlatformUserMapper;
import com.coding.data.mapper.auth.PlatformUserRoleMapper;
import com.coding.data.mapper.auth.PlatformUserTenantRoleMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

/**
 * platform-api 侧共享安全 bean：BCrypt 与 auth-server 登录校验保持一致（DB 中 hash 均为 $2a$）
 */
@Configuration(proxyBeanMethods = false)
public class SecurityBeans {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 权限闭包计算（唯一实现，见该类 javadoc）。刻意在此注册而不是在 platform-common 打 {@code @Service}：
     * 三个应用都扫 {@code com.coding.common}，打注解会让 k8s-server 也拿到一个"能查业务权限表"的 bean，
     * 与它零业务逻辑的定位相悖。同样的注册见 platform-auth（回滚开关用）。
     *
     * <p>由 {@link CachedPermissionClosureResolver} 消费（TTL 缓存）；{@code /user/me} 也走同一个解析器，
     * 保证"菜单显示"与"接口判权"用的是同一份闭包。
     */
    @Bean
    public PermissionClosureService permissionClosureService(PlatformUserMapper userMapper,
                                                             PlatformUserRoleMapper userRoleMapper,
                                                             PlatformUserTenantRoleMapper userTenantRoleMapper,
                                                             PlatformPermissionMapper permissionMapper) {
        return new PermissionClosureService(userMapper, userRoleMapper, userTenantRoleMapper, permissionMapper);
    }

    /** 跨实例共享的 TTL 缓存。类在 platform-common（网关也用），故在此注册而非打注解（理由同上）。 */
    @Bean
    public RedisJsonCache redisJsonCache(StringRedisTemplate redis, JsonMapper jsonMapper) {
        return new RedisJsonCache(redis, jsonMapper);
    }

    /** 权限闭包解析（TTL 缓存）。网关注册同一个类、同一份 Redis，键形状是两边的契约。 */
    @Bean
    public JwtPermissionResolver permissionClosureResolver(PermissionClosureService closureService,
                                                           RedisJsonCache cache,
                                                           @Value("${platform.permissions.cache-ttl-seconds:30}") long ttlSeconds) {
        return new CachedPermissionClosureResolver(closureService, cache, Duration.ofSeconds(ttlSeconds));
    }
}
