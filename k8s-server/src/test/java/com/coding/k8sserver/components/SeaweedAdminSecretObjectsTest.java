package com.coding.k8sserver.components;

import io.fabric8.kubernetes.api.model.Secret;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SeaweedAdminSecretObjectsTest {

    @Test
    void buildProducesTheKeysTheChartExpects() {
        // chart 的 admin.secret.userKey / pwKey 指向这两个键；名字错了管理台就起不来
        Secret secret = SeaweedAdminSecretObjects.build("platform-storage", "platform-seaweedfs-admin");
        assertThat(secret.getMetadata().getName()).isEqualTo("platform-seaweedfs-admin");
        assertThat(secret.getMetadata().getNamespace()).isEqualTo("platform-storage");
        assertThat(secret.getStringData())
                .containsKeys(SeaweedAdminSecretObjects.USERNAME_KEY, SeaweedAdminSecretObjects.PASSWORD_KEY);
        assertThat(secret.getStringData().get(SeaweedAdminSecretObjects.USERNAME_KEY))
                .isEqualTo(SeaweedAdminSecretObjects.DEFAULT_USERNAME);
        assertThat(secret.getStringData().get(SeaweedAdminSecretObjects.PASSWORD_KEY)).hasSizeGreaterThanOrEqualTo(24);
    }

    @Test
    void passwordsAreRandomPerBuild() {
        String a = SeaweedAdminSecretObjects.build("ns", "s").getStringData()
                .get(SeaweedAdminSecretObjects.PASSWORD_KEY);
        String b = SeaweedAdminSecretObjects.build("ns", "s").getStringData()
                .get(SeaweedAdminSecretObjects.PASSWORD_KEY);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void hasCredentialsRequiresBothKeys() {
        // 已存在的 Secret 只要有完整凭证就原样保留（升级不轮换）
        Map<String, String> both = new HashMap<>();
        both.put(SeaweedAdminSecretObjects.USERNAME_KEY, "admin");
        both.put(SeaweedAdminSecretObjects.PASSWORD_KEY, "x");
        assertThat(SeaweedAdminSecretObjects.hasCredentials(both)).isTrue();

        Map<String, String> onlyUser = new HashMap<>();
        onlyUser.put(SeaweedAdminSecretObjects.USERNAME_KEY, "admin");
        assertThat(SeaweedAdminSecretObjects.hasCredentials(onlyUser)).isFalse();

        Map<String, String> blankPw = new HashMap<>();
        blankPw.put(SeaweedAdminSecretObjects.USERNAME_KEY, "admin");
        blankPw.put(SeaweedAdminSecretObjects.PASSWORD_KEY, " ");
        assertThat(SeaweedAdminSecretObjects.hasCredentials(blankPw)).isFalse();
        assertThat(SeaweedAdminSecretObjects.hasCredentials(null)).isFalse();
    }
}
