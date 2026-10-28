package com.coding.k8sserver.middleware;

import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「版本」列的数据来源：没有版本下拉的中间件（Redis/MySQL/PG/RabbitMQ/Nacos）台账里没有版本，
 * 只能从线上推 —— 取首个 Pod 首个容器的镜像 tag。
 */
class MiddlewareAdapterVersionTest {

    private final MiddlewareAdapter adapter = new RedisMiddlewareAdapter();

    private static Pod pod(String... images) {
        var containers = java.util.Arrays.stream(images)
                .map(img -> new ContainerBuilder().withName("c" + img.hashCode()).withImage(img).build())
                .toList();
        return new PodBuilder().withNewMetadata().withName("p").endMetadata()
                .withNewSpec().withContainers(containers).endSpec().build();
    }

    @Test
    void versionComesFromTheMainContainerImage() {
        // 主容器在前、exporter 边车在后：不能取错成 exporter 的 tag
        var pods = List.of(pod("docker.io/redis:7.2.5", "quay.io/prometheuscommunity/exporter:v1.7.0"));

        assertThat(adapter.deployedVersion(null, pods)).isEqualTo("7.2.5");
    }

    @Test
    void versionIsNullWhenThereAreNoPodsYet() {
        // 还没起来（CREATING）：调用方保留台账值，不要报一个假版本
        assertThat(adapter.deployedVersion(null, List.of())).isNull();
        assertThat(adapter.deployedVersion(null, null)).isNull();
    }

    @Test
    void firstImageSkipsPodsWithoutContainers() {
        Pod empty = new PodBuilder().withNewMetadata().withName("pending").endMetadata()
                .withNewSpec().endSpec().build();
        var pods = List.of(empty, pod("ghcr.io/cloudnative-pg/postgresql:16.4"));

        assertThat(MiddlewareAdapter.firstImage(pods)).isEqualTo("ghcr.io/cloudnative-pg/postgresql:16.4");
    }

    @Test
    void imageTagHandlesRegistryPortsDigestsAndTheChartPrefix() {
        assertThat(MiddlewareAdapter.imageTag("percona/percona-xtradb-cluster:8.0.36-28.1"))
                .isEqualTo("8.0.36-28.1");
        // chart 的 appVersion 带 v：与台账里 2.4.3 的写法统一
        assertThat(MiddlewareAdapter.imageTag("nacos/nacos-server:v2.4.3")).isEqualTo("2.4.3");
        assertThat(MiddlewareAdapter.imageTag("milvusdb/milvus:v2.6.25")).isEqualTo("2.6.25");
        // digest 跟在 tag 后面：只取 tag
        assertThat(MiddlewareAdapter.imageTag("ghcr.io/cloudnative-pg/postgresql:16.4@sha256:abc123"))
                .isEqualTo("16.4");
        // 没有 tag / 冒号是端口号 / 只有 digest → 都推不出
        assertThat(MiddlewareAdapter.imageTag("repo/app")).isNull();
        assertThat(MiddlewareAdapter.imageTag("registry:5000/app")).isNull();
        assertThat(MiddlewareAdapter.imageTag("repo/app@sha256:abc123")).isNull();
        assertThat(MiddlewareAdapter.imageTag(null)).isNull();
        // 不是 v+数字的别乱剥（latest / v2-canary 原样）
        assertThat(MiddlewareAdapter.imageTag("repo/app:latest")).isEqualTo("latest");
        assertThat(MiddlewareAdapter.imageTag("repo/app:v2-canary")).isEqualTo("v2-canary");
    }
}
