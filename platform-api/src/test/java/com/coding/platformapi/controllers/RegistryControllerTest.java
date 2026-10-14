package com.coding.platformapi.controllers;

import com.coding.platformapi.audit.Auditable;
import com.coding.platformapi.models.RegistryConnectionView;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⚠️ **结构性钉子**：窄投影 DTO 不得承载凭据字段；三个入口不得标审计。
 *
 * <p>照 S0.2 的 {@code CredentialServiceTest.viewDtoHasNoCredentialField} 的写法 ——
 * 「永不回显」是否定性契约，逐个断言守不住（将来有人加字段时旧断言全绿），只能反射扫。
 */
class RegistryControllerTest {

    /** 窄投影里不该出现的字段名片段；本 DTO 连 `hasSecret` 那种「是否已配置」标志都没有 */
    private static final List<String> BANNED =
            List.of("secret", "password", "passwd", "username", "credential", "fingerprint", "auth");

    private static List<String> fieldNames(Class<?> c) {
        return Arrays.stream(c.getDeclaredFields())
                .map(Field::getName)
                .map(n -> n.toLowerCase(Locale.ROOT))
                .toList();
    }

    @Test
    void connectionViewCarriesNoCredentialField() {
        List<String> offenders = fieldNames(RegistryConnectionView.class).stream()
                .filter(n -> BANNED.stream().anyMatch(n::contains))
                .toList();

        assertThat(offenders)
                .as("浏览页的连接投影不得承载凭据内容（spec §5.1）；它只应有 id/name/type/endpoint")
                .isEmpty();
    }

    @Test
    void connectionViewHasExactlyFourFields() {
        assertThat(RegistryConnectionView.class.getDeclaredFields())
                .extracting(Field::getName)
                .containsExactlyInAnyOrder("id", "name", "type", "endpoint");
    }

    @Test
    void exposesExactlyTheThreeReadOnlyEntries() {
        assertThat(Arrays.stream(RegistryController.class.getDeclaredMethods()).map(Method::getName))
                .contains("suggest", "browse", "connections");
    }

    /**
     * spec §8 的有意行为：**三个入口都不标审计** —— 补全是每按键一次的高频只读动作，
     * 逐次写审计等于淹掉审计表，且它不改任何状态。
     *
     * <p>这条钉的是「将来有人顺手给它加上 `@Auditable`」——那会让审计表被一个只读动作击穿，
     * 而且看起来像是在「加强留痕」，评审时很容易被放过去。
     */
    @Test
    void noneOfTheEntriesIsAudited() {
        List<String> audited = Arrays.stream(RegistryController.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Auditable.class))
                .map(Method::getName)
                .toList();

        assertThat(audited)
                .as("镜像仓库的三个入口都不得标审计（spec §8）；确有需要时先改 spec，不是直接加注解")
                .isEmpty();
    }
}
