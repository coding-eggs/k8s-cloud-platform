package com.coding.platformapi.configs;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "spring.security.oauth2.resourceserver.jwt")
public class OAuth2JwtProperties {

    private String type;

    private Secret jws;

    private Secret jwe;


    @Data
    public static class Secret {
        private String secret;

        private String secretAlg;

        private String encMethod;
    }


}
