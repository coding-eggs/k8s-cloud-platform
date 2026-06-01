package com.coding.auth;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScans;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@EnableScheduling
@EnableTransactionManagement
@ComponentScan(basePackages = "com.coding")
//@ComponentScan(basePackages = {"com.coding.common", "com.coding.auth", "com.coding.data"})
@MapperScan({
        "com.coding.data.mapper",
})
@SpringBootApplication
public class PlatformAuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformAuthApplication.class, args);
    }

}
