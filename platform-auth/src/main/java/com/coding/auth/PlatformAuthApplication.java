package com.coding.auth;

import com.coding.common.components.JwkService;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScans;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.util.List;

@EnableScheduling
@EnableTransactionManagement
@ComponentScan(basePackages = {"com.coding.common", "com.coding.auth", "com.coding.data"})
@MapperScan({
        "com.coding.data.mapper",
})
@SpringBootApplication
public class PlatformAuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformAuthApplication.class, args);
    }


    //暴漏给JWK Endpoint 或者给JwtEncoder 、 JwtDecoder 使用， 目前jwkSource是从配置文件里读取的
    @Bean
    public JWKSource<SecurityContext> jwkSource(JwkService jwkService) {
        return (jwkSelector, securityContext) -> {
            List<JWK> jwkList = jwkService.loadAllJwkFromDb();
            JWKSet jwkSet = new JWKSet(jwkList);
            return jwkSelector.select(jwkSet);
        };
    }
}
