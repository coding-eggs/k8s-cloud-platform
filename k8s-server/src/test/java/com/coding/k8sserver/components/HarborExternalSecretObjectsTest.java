package com.coding.k8sserver.components;

import io.fabric8.kubernetes.api.model.Secret;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HarborExternalSecretObjectsTest {

    @Test
    void databaseSecretUsesThePasswordKeyTheChartExpects() {
        Secret secret = HarborExternalSecretObjects.buildDatabase("harbor",
                "platform-harbor-external-db", "db-pw");
        assertThat(secret.getStringData())
                .containsExactly(Map.entry(HarborExternalSecretObjects.DB_PASSWORD_KEY, "db-pw"));
        assertThat(secret.getMetadata().getLabels())
                .containsEntry("platform.coding/component", "harbor");
    }

    @Test
    void redisSecretCarriesThePasswordAndOptionalUsername() {
        Secret withUser = HarborExternalSecretObjects.buildRedis("harbor",
                "platform-harbor-external-redis", "harbor-user", "redis-pw");
        assertThat(withUser.getStringData())
                .containsEntry(HarborExternalSecretObjects.REDIS_PASSWORD_KEY, "redis-pw")
                .containsEntry(HarborExternalSecretObjects.REDIS_USERNAME_KEY, "harbor-user");

        // 默认用户：不写 REDIS_USERNAME，chart 会按无 ACL 处理
        Secret noUser = HarborExternalSecretObjects.buildRedis("harbor",
                "platform-harbor-external-redis", null, "redis-pw");
        assertThat(noUser.getStringData())
                .containsExactly(Map.entry(HarborExternalSecretObjects.REDIS_PASSWORD_KEY, "redis-pw"));
    }

    @Test
    void hasValueRejectsMissingOrBlank() {
        Secret secret = HarborExternalSecretObjects.buildDatabase("harbor", "s", "db-pw");
        assertThat(HarborExternalSecretObjects.hasValue(secret,
                HarborExternalSecretObjects.DB_PASSWORD_KEY)).isTrue();
        assertThat(HarborExternalSecretObjects.hasValue(secret,
                HarborExternalSecretObjects.REDIS_PASSWORD_KEY)).isFalse();
        assertThat(HarborExternalSecretObjects.hasValue(null,
                HarborExternalSecretObjects.DB_PASSWORD_KEY)).isFalse();
    }
}
