package com.coding.platformapi.registry;

import com.coding.common.exception.CloudPlatformException;
import com.coding.platformapi.services.CredentialService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 分派表 + **护栏**。
 *
 * <p>护栏这条是本批「扩展位」承诺的兑现方式：将来有人给 {@code CredentialService.TYPES}
 * 加了枚举值却忘了写实现，**构建期就红**，而不是等运行时才炸。
 */
class RegistryDialectRegistryTest {

    /** 一个只为测试存在的方言 */
    static class FakeDialect implements RegistryDialect {
        @Override public String type() { return "FAKE"; }
        @Override public List<String> suggest(CredentialRef c, String p) { return List.of(); }
        @Override public BrowseResult browse(CredentialRef c, List<String> p) {
            return new BrowseResult(List.of(), List.of());
        }
    }

    RegistryDialectRegistry registry =
            new RegistryDialectRegistry(List.of(new FakeDialect(), new HarborDialect()));

    /** 不是镜像仓库的类型，不需要方言 */
    private static final Set<String> NON_REGISTRY_TYPES = Set.of("GIT");

    @Test
    void dispatchesByType() {
        assertThat(registry.of("FAKE")).isInstanceOf(FakeDialect.class);
        assertThat(registry.of("HARBOR")).isInstanceOf(HarborDialect.class);
    }

    @Test
    void unknownTypeFailsWithABusinessCode() {
        assertThatThrownBy(() -> registry.of("GITEA"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("GITEA");
    }

    /**
     * ⚠️ 护栏：{@code CredentialService.TYPES} 里**每一个镜像仓库类型**都必须有 dialect 实现。
     *
     * <p>用反射读 {@code TYPES} 是为了它保持 private（不外泄实现细节），同时这条断言仍然能盯着它。
     *
     * <p>{@code GIT} 被排除：它是 git 连接，本来就不该有镜像仓库方言（spec §10 的「本批只实现 Harbor」）。
     */
    @Test
    void everyConfiguredRegistryTypeHasADialect() throws Exception {
        Set<String> types = configuredTypes().stream()
                .filter(t -> !NON_REGISTRY_TYPES.contains(t))
                .collect(Collectors.toSet());

        assertThat(types)
                .as("CredentialService.TYPES 里出现了没有 RegistryDialect 实现的镜像仓库类型：%s", types)
                .isNotEmpty()
                .allSatisfy(t -> assertThat(registry.of(t)).isNotNull());
    }

    @SuppressWarnings("unchecked")
    private static Set<String> configuredTypes() throws Exception {
        Field f = CredentialService.class.getDeclaredField("TYPES");
        f.setAccessible(true);
        return (Set<String>) f.get(null);
    }
}
