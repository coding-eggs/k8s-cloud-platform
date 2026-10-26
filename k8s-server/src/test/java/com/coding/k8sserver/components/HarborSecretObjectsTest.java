package com.coding.k8sserver.components;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HarborSecretObjectsTest {

    @Test
    void buildsBothKeysWithTheRequiredLengths() {
        var secret = HarborSecretObjects.build("harbor", "platform-harbor-secrets");
        Map<String, String> data = secret.getStringData();
        assertThat(data).containsKeys(HarborSecretObjects.ADMIN_PASSWORD_KEY,
                HarborSecretObjects.SECRET_KEY);
        assertThat(data.get(HarborSecretObjects.SECRET_KEY)).hasSize(16);
        assertThat(data.get(HarborSecretObjects.ADMIN_PASSWORD_KEY).length()).isGreaterThanOrEqualTo(24);
    }

    @Test
    void hasAllKeysIsFalseWhenEitherKeyIsMissingOrBlank() {
        assertThat(HarborSecretObjects.hasAllKeys(Map.of(
                HarborSecretObjects.ADMIN_PASSWORD_KEY, "x",
                HarborSecretObjects.SECRET_KEY, "0123456789abcdef"))).isTrue();
        assertThat(HarborSecretObjects.hasAllKeys(Map.of(
                HarborSecretObjects.ADMIN_PASSWORD_KEY, "x"))).isFalse();
        assertThat(HarborSecretObjects.hasAllKeys(Map.of(
                HarborSecretObjects.ADMIN_PASSWORD_KEY, " ",
                HarborSecretObjects.SECRET_KEY, "0123456789abcdef"))).isFalse();
        assertThat(HarborSecretObjects.hasAllKeys(null)).isFalse();
    }

    @Test
    void secretKeyIsAlwaysSixteenChars() {
        for (int i = 0; i < 50; i++) {
            assertThat(HarborSecretObjects.randomSecretKey()).hasSize(16);
        }
    }
}
