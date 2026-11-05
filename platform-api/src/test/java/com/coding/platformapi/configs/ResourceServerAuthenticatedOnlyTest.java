package com.coding.platformapi.configs;

import com.coding.common.components.jwt.JwtPermissionResolver;
import com.coding.common.components.permission.PermissionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 网关上线后 api 只校验登录：接口级权限（权限表）由网关判，api 不再按码拒绝（网关 spec §8）。
 * ⚠️ 前提是 api 端口只对网关开放（NetworkPolicy），否则等于对所有登录用户全开。
 */
@SpringJUnitWebConfig(ResourceServerAuthenticatedOnlyTest.Slice.class)
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/oauth2/jwks",
        "spring.security.oauth2.resourceserver.jwt.audiences=platform-web-client",
        "spring.security.ignore-urls=/alert/webhook"})
class ResourceServerAuthenticatedOnlyTest {

    @EnableWebMvc
    @Configuration
    @EnableConfigurationProperties
    @Import({ResourceServerConfig.class, OAuth2JwtProperties.class, Probe.class})
    static class Slice {

        @Bean
        JwtPermissionResolver jwtPermissionResolver() {
            return JwtPermissionResolver.noop();
        }

        /** 这条接口在权限表里要码 platform:workload:manage —— 持码与否不再影响 api 的判定 */
        @Bean
        PermissionRegistry permissionRegistry() {
            return new PermissionRegistry(List.of(
                    new PermissionRegistry.Rule("platform-api", "POST", "/probe/delete", "platform:workload:manage")));
        }
    }

    @RestController
    static class Probe {

        @PostMapping("/probe/delete")
        String delete() {
            return "deleted";
        }

        @PostMapping("/probe/unlisted")
        String unlisted() {
            return "unlisted";
        }

        @PostMapping("/alert/webhook")
        String webhook() {
            return "received";
        }
    }

    @Autowired
    WebApplicationContext ctx;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
    }

    @Test
    void authenticatedWithoutCodeReachesController() throws Exception {
        mvc.perform(post("/probe/delete").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(content().string("deleted"));
    }

    /** 不在权限表、也不在豁免里的路径：以前一律拒绝，现在只要登录 */
    @Test
    void unknownPathOnlyNeedsLogin() throws Exception {
        mvc.perform(post("/probe/unlisted").with(jwt())).andExpect(status().isOk());
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mvc.perform(post("/probe/delete")).andExpect(status().isUnauthorized());
    }

    /** Alertmanager 推送：无 JWT，仍放行到控制器（共享密钥由控制器自校验） */
    @Test
    void alertWebhookStillPermitted() throws Exception {
        // servletPath 与真实部署一致（DispatcherServlet 挂在 /，servletPath = 整条路径）：Bearer 解析器按它跳过本路径
        mvc.perform(post("/alert/webhook").servletPath("/alert/webhook").header("Authorization", "Bearer shared-secret"))
                .andExpect(status().isOk())
                .andExpect(content().string("received"));
    }
}
