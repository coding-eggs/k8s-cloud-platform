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
    void requiredCrdsMatchEachOperatorsNaming() {
        assertThat(catalog.require("mysql").requiredCrds())
                .containsExactly("perconaservermysqls.ps.percona.com");
        assertThat(catalog.require("kafka").requiredCrds())
                .containsExactly("kafkas.kafka.strimzi.io", "kafkanodepools.kafka.strimzi.io");
        assertThat(catalog.require("elasticsearch").requiredCrds())
                .containsExactly("elasticsearches.elasticsearch.k8s.elastic.co");
        assertThat(catalog.require("postgresql").requiredCrds())
                .containsExactly("clusters.postgresql.cnpg.io");
        assertThat(catalog.require("rabbitmq").requiredCrds())
                .containsExactly("rabbitmqclusters.rabbitmq.com");
    }

    @Test
    void rabbitmqDoesNotInstallTheTopologyOperator() {
        // 平台不提供 Binding/Queue 这类拓扑对象，多装一个 Operator 只是多拉两个镜像
        assertThat(catalog.require("rabbitmq").helmValues())
                .contains("msgTopologyOperator")
                .contains("enabled: false");
    }

    @Test
    void nacosIsNotOperatorBacked() {
        assertThatThrownBy(() -> catalog.require("nacos")).hasMessageContaining("不支持");
    }

    @Test
    void unknownCodeIsRejected() {
        assertThatThrownBy(() -> catalog.require("minio")).hasMessageContaining("不支持");
    }
}
