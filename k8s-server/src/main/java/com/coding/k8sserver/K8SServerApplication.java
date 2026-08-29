package com.coding.k8sserver;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Slf4j
@EnableScheduling
@EnableTransactionManagement
@ComponentScan(basePackages = {"com.coding.k8score", "com.coding.k8sserver", "com.coding.common", "com.coding.data"})
@MapperScan({
        "com.coding.data.mapper",
})
@SpringBootApplication
public class K8SServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(K8SServerApplication.class, args);
    }

}
