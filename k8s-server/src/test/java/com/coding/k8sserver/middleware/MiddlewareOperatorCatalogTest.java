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
    void milvusShipsCrdsInTemplatesAndDisablesWebhook() {
        var entry = catalog.require("milvus");
        assertThat(entry.chartVersion()).isEqualTo("1.3.12");
        assertThat(entry.requiredCrds()).containsExactly(
                "milvuses.milvus.io", "milvusclusters.milvus.io", "milvusupgrades.milvus.io");
        // CRD 在 templates/ 里：Helm 会给它们打 release 注解，重装时按"本 release 的 CRD"接管
        assertThat(entry.crdsShipInCrdsDir()).isFalse();
        assertThat(entry.helmValues())
                .contains("enableWebhook: false")
                .contains("installCRDs: true")
                .contains("tag: v1.3.12");
    }

    @Test
    void unknownCodeIsRejected() {
        assertThatThrownBy(() -> catalog.require("minio")).hasMessageContaining("不支持");
    }
}
