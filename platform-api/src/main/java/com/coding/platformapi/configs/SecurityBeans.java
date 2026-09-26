package com.coding.platformapi.configs;

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
}
