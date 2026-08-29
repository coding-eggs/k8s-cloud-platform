package com.coding.platformapi;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 平台管理 API：集群纳管、租户管理、命名空间分配、RBAC 模板管理
 */
@EnableTransactionManagement
@ComponentScan(basePackages = {"com.coding.platformapi", "com.coding.common", "com.coding.data"})
@MapperScan({
        "com.coding.data.mapper",
})
@SpringBootApplication
public class PlatformApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApiApplication.class, args);
    }
}
