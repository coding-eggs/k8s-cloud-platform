package com.coding.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformGatewayApplicationTest {

    @Autowired
    ApplicationContext ctx;

    /** 网关必须按 WebFlux 起；classpath 上意外混进 webmvc 时 Boot 会改按 servlet 启动（Global Constraints） */
    @Test
    void startsAsReactive() {
        assertThat(ctx).isInstanceOf(ReactiveWebServerApplicationContext.class);
    }

    /** platform-common 的 servlet-api 是 provided，不应传递进来；一旦传进来，公共库里的 servlet 适配就会被误加载 */
    @Test
    void noServletApiOnClasspath() {
        assertThat(ClassUtils.isPresent("jakarta.servlet.http.HttpServletRequest", null)).isFalse();
    }
}
