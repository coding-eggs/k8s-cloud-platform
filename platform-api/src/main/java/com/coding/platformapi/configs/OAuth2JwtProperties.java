package com.coding.platformapi.configs;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "spring.security.oauth2.resourceserver.jwt")
public class OAuth2JwtProperties {

    private String type;

    /** 允许的 aud（平台自有客户端的 client_id）。由 ResourceServerConfig 显式装进校验器；为空拒启。 */
    private List<String> audiences;

    private Secret jws;

    private Secret jwe;


    @Data
    public static class Secret {
        private String secret;

        private String secretAlg;

        private String encMethod;
    }


}
