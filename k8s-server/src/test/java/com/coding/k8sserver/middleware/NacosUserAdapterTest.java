package com.coding.k8sserver.middleware;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nacos 的纯函数部分：表单编码 / 登录响应 / 用户列表解析。
 */
class NacosUserAdapterTest {

    private final NacosUserAdapter adapter = new NacosUserAdapter();

    @Test
    void formEncodesFieldsAndKeepsTheFieldNames() {
        assertThat(NacosUserAdapter.form(Map.of("username", "alice", "password", "p&w=1")))
                .contains("username=alice")
                .contains("password=p%26w%3D1");
    }

    @Test
    void accessTokenIsReadFromEitherShape() {
        assertThat(NacosUserAdapter.accessTokenOf("{\"accessToken\":\"tok-1\"}")).isEqualTo("tok-1");
        assertThat(NacosUserAdapter.accessTokenOf("{\"data\":{\"accessToken\":\"tok-2\"}}")).isEqualTo("tok-2");
        assertThat(NacosUserAdapter.accessTokenOf("{}")).isEmpty();
    }

    @Test
    void userNamesAreReadFromPageItemsOrPlainArray() {
        String page = "{\"data\":{\"pageItems\":[{\"username\":\"nacos\"},{\"username\":\"alice\"}]}}";
        String plain = "{\"data\":[{\"username\":\"bob\"}]}";

        assertThat(NacosUserAdapter.parseUsers(page)).containsExactly("nacos", "alice");
        assertThat(NacosUserAdapter.parseUsers(plain)).containsExactly("bob");
    }

    @Test
    void baseUrlUsesTheChartFixedServiceName() {
        assertThat(NacosUserAdapter.baseUrl("mw-nacos-abcdefgh", "demo"))
                .isEqualTo("http://nacos-cs.demo.svc:8848");
    }

    @Test
    void onlyTheAdministratorRoleIsOffered() {
        assertThat(adapter.roles(new MiddlewareUserRef("mw-nacos-abcdefgh", "demo", "mwi_1")))
                .containsExactly("管理员");
    }
}
