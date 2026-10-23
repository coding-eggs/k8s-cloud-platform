package com.coding.k8sserver.middleware;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MiddlewareStatusEvaluatorTest {

    @Test
    void allPodsReadyIsReady() {
        var r = MiddlewareStatusEvaluator.evaluate(3, 3, 3, false, null);

        assertThat(r.status()).isEqualTo("READY");
        assertThat(r.message()).isEqualTo("运行中");
    }

    @Test
    void fewerPodsThanDesiredIsDegraded() {
        // 真集群验证：Kafka 3 个 broker 只起来 2 个（第三个被配额挡住），
        // 按"所有现存 Pod 都就绪"判定会误报 READY
        var r = MiddlewareStatusEvaluator.evaluate(2, 2, 3, false, null);

        assertThat(r.status()).isEqualTo("DEGRADED");
        assertThat(r.message()).contains("2/3");
    }

    @Test
    void unknownDesiredFallsBackToCurrentPods() {
        var r = MiddlewareStatusEvaluator.evaluate(2, 2, null, false, null);

        assertThat(r.status()).isEqualTo("READY");
    }

    @Test
    void initializingWithoutPodsIsCreating() {
        // MySQL 首启要 bootstrap Group Replication，期间 Pod 可能还没被 selector 命中
        var r = MiddlewareStatusEvaluator.evaluate(0, 0, 1, true, null);

        assertThat(r.status()).isEqualTo("CREATING");
        assertThat(r.message()).contains("初始化");
    }

    @Test
    void operatorFailureWithoutPodsIsFailed() {
        var r = MiddlewareStatusEvaluator.evaluate(0, 0, 1, false, "Unsupported Kafka version");

        assertThat(r.status()).isEqualTo("FAILED");
        assertThat(r.message()).contains("Unsupported Kafka version");
    }

    @Test
    void noPodsAndNoSignalIsCreating() {
        assertThat(MiddlewareStatusEvaluator.evaluate(0, 0, null, false, null).status())
                .isEqualTo("CREATING");
    }

    @Test
    void readyPodsWithOperatorWarningIsDegraded() {
        // Pod 都起来了但 Operator 仍报错（如 Strimzi 超时后自动补齐），不能谎报 READY
        var r = MiddlewareStatusEvaluator.evaluate(3, 3, 3, false, "Exceeded timeout of 300000ms");

        assertThat(r.status()).isEqualTo("DEGRADED");
        assertThat(r.message()).contains("Exceeded timeout");
    }

    @Test
    void partiallyReadyPodsReportCounts() {
        var r = MiddlewareStatusEvaluator.evaluate(3, 1, 3, false, null);

        assertThat(r.status()).isEqualTo("DEGRADED");
        assertThat(r.message()).isEqualTo("1/3 就绪");
    }
}
