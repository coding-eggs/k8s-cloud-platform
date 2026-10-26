package com.coding.k8sserver.middleware;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Elasticsearch 的纯函数部分：内置角色映射 / 用户体 / 平台账号前缀。
 *
 * <p>ES 的用户名空间是全局的，平台建的账号统一加 {@code platform_} 前缀，列表再剥掉显示。
 */
class ElasticsearchUserAdapterTest {

    @Test
    void roleMapsToBuiltinEsRoles() {
        assertThat(ElasticsearchUserAdapter.esRoles("只读")).containsExactly("viewer");
        assertThat(ElasticsearchUserAdapter.esRoles("读写")).containsExactly("editor");
        assertThat(ElasticsearchUserAdapter.esRoles("管理员")).containsExactly("superuser");
    }

    @Test
    void userBodyCarriesPasswordAndRoles() {
        Map<String, Object> body = ElasticsearchUserAdapter.userBody("pw-123456", "只读");

        assertThat(body)
                .containsEntry("password", "pw-123456")
                .containsEntry("roles", java.util.List.of("viewer"));
    }

    @Test
    void onlyElasticAndPlatformPrefixedUsersAreShown() {
        assertThat(ElasticsearchUserAdapter.isShown("elastic")).isTrue();
        assertThat(ElasticsearchUserAdapter.isShown("platform_alice")).isTrue();
        assertThat(ElasticsearchUserAdapter.isShown("kibana_system")).isFalse();
        assertThat(ElasticsearchUserAdapter.isShown("logstash_system")).isFalse();
    }

    @Test
    void esUsernameAddsPrefixAndDisplayStripsIt() {
        assertThat(ElasticsearchUserAdapter.esUsername("alice")).isEqualTo("platform_alice");
        assertThat(ElasticsearchUserAdapter.displayName("platform_alice")).isEqualTo("alice");
        assertThat(ElasticsearchUserAdapter.displayName("elastic")).isEqualTo("elastic");
    }

    @Test
    void unknownRoleIsRejectedInsteadOfFallingBack() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> ElasticsearchUserAdapter.esRoles("admin"))
                .isInstanceOf(com.coding.common.exception.CloudPlatformException.class)
                .hasMessageContaining("不支持的角色");
    }
}
