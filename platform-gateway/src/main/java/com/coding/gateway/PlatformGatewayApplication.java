package com.coding.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 平台 API 网关（spec 2026-11-03-platform-gateway-design）。
 *
 * <p>只扫本模块：platform-common 里有 servlet 专属的配置（审计切面、AuthContext），网关不能把它们拉进来；
 * 需要的公共类在本模块里显式 {@code @Bean} 注册。
 */
@SpringBootApplication
public class PlatformGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformGatewayApplication.class, args);
    }
}
