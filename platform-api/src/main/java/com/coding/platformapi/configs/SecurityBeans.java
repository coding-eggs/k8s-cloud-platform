package com.coding.platformapi.configs;

import com.coding.common.components.jwt.PermissionClosureService;
import com.coding.data.mapper.auth.PlatformPermissionMapper;
import com.coding.data.mapper.auth.PlatformUserMapper;
import com.coding.data.mapper.auth.PlatformUserRoleMapper;
import com.coding.data.mapper.auth.PlatformUserTenantRoleMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

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
     * <p>由 {@code PermissionClosureResolver} 消费（TTL 缓存）；{@code /user/me} 也走同一个解析器，
     * 保证"菜单显示"与"接口判权"用的是同一份闭包。
     */
    @Bean
    public PermissionClosureService permissionClosureService(PlatformUserMapper userMapper,
                                                             PlatformUserRoleMapper userRoleMapper,
                                                             PlatformUserTenantRoleMapper userTenantRoleMapper,
                                                             PlatformPermissionMapper permissionMapper) {
        return new PermissionClosureService(userMapper, userRoleMapper, userTenantRoleMapper, permissionMapper);
    }
}
