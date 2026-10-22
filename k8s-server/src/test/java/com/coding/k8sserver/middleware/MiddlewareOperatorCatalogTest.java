package com.coding.k8sserver.middleware;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MiddlewareOperatorCatalogTest {

    private final MiddlewareOperatorCatalog catalog = new MiddlewareOperatorCatalog();

    @Test
    void redisCrdsAreNamedPluralDotGroup() {
        // CRD 名是 <plural>.<group>：group 自己就带 .redis.opstreelabs.in，漏掉一个 plural 会永远判未安装
        assertThat(catalog.require("redis").requiredCrds()).containsExactly(
                "redis.redis.redis.opstreelabs.in",
                "redisreplications.redis.redis.opstreelabs.in",
                "redissentinels.redis.redis.opstreelabs.in",
                "redisclusters.redis.redis.opstreelabs.in");
    }

    @Test
    void unknownCodeIsRejected() {
        assertThatThrownBy(() -> catalog.require("minio")).hasMessageContaining("不支持");
    }
}
