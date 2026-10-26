package com.coding.platformapi.services;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用户管理的平台侧规则：支持清单、用户名、口令长度。
 *
 * <p>这三条是三家（前端表单、platform-api、k8s-server）共同口径的唯一定义处，
 * 后面每个适配器都按它校验，不再各自发明一套。
 */
class MiddlewareUserSupportTest {

    @Test
    void supportedCodesCoverTheSevenImplementedMiddlewares() {
        assertThat(MiddlewareUserService.SUPPORTED_CODES).containsExactlyInAnyOrder(
                "mysql", "postgresql", "redis", "rabbitmq", "elasticsearch", "milvus", "kafka");
    }

    @Test
    void nacosStaysOutUntilItsAdminAndRoleApisAreVerified() {
        // chart 没有管理员口令入口、3.x 初始化与 role API 未实测 —— 支持清单里不能有它，
        // 否则 UI 会给出一个登不进去的用户页签（spec §6.8 降级）
        assertThat(MiddlewareUserService.SUPPORTED_CODES).doesNotContain("nacos");
    }

    @Test
    void usernamesAreLowercaseWithUnderscoresOnly() {
        assertThat(MiddlewareUserService.validUsername("alice_1")).isTrue();
        assertThat(MiddlewareUserService.validUsername("Alice")).isFalse();
        assertThat(MiddlewareUserService.validUsername("al")).isFalse();      // 太短
        assertThat(MiddlewareUserService.validUsername("a-b")).isFalse();     // 连字符
        assertThat(MiddlewareUserService.validUsername("x".repeat(33))).isFalse();
    }

    @Test
    void passwordsAreEightToSeventyTwo() {
        assertThatThrownBy(() -> MiddlewareUserService.requirePasswordLength("short"))
                .hasMessageContaining("8~72");
        assertThatThrownBy(() -> MiddlewareUserService.requirePasswordLength("x".repeat(73)))
                .hasMessageContaining("8~72");
    }
}
