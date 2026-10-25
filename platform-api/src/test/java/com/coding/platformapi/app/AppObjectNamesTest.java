package com.coding.platformapi.app;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppObjectNamesTest {

    @Test
    void slugifiesChineseAndSymbols() {
        assertThat(AppObjectNames.slug("我的 应用_v1")).isEqualTo("v1");
        assertThat(AppObjectNames.slug("Demo App")).isEqualTo("demo-app");
        assertThat(AppObjectNames.slug("--a--b--")).isEqualTo("a--b");
    }

    @Test
    void truncatesLongSlugWithoutTrailingDash() {
        String slug = AppObjectNames.slug("a".repeat(40));
        assertThat(slug).hasSize(30);
        assertThat(slug).doesNotEndWith("-");
    }

    @Test
    void rejectsSlugWithNothingUsable() {
        assertThatThrownBy(() -> AppObjectNames.slug("中间件"))
                .hasMessageContaining("应用名至少要包含一个字母或数字");
    }

    @Test
    void buildsObjectNameAndKeepsItUnder63() {
        assertThat(AppObjectNames.objectName("demo", "web")).isEqualTo("demo-web");
        String name = AppObjectNames.objectName("a".repeat(30), "b".repeat(20));
        assertThat(name.length()).isLessThanOrEqualTo(63);
        assertThat(name).startsWith("a");
        assertThat(name).contains("-");
    }

    @Test
    void uppercasesNodeKeyForEnvPrefix() {
        assertThat(AppObjectNames.envPrefix("mysql-main")).isEqualTo("MYSQL_MAIN");
    }
}
