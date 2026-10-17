package com.coding.platformapi.components;

import com.coding.common.exception.CloudPlatformException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * recipe values 的占位符替换。
 *
 * <p>为什么需要：`externalLabels.cluster_name`、`remoteWrite[0].url` 这类契约值按集群变化，
 * 写不进静态目录。但**解析失败必须报错**——尤其是 URL 解析成空串：装出来的 Prometheus
 * 永远上报不了，且没有任何运行时报错。
 */
class ValueTemplateTest {

    @Test
    void replacesNestedPlaceholders() {
        Map<String, Object> in = Map.of("prometheus", Map.of("prometheusSpec",
                Map.of("externalLabels", Map.of("cluster_name", "${cluster.name}"))));

        Object out = ValueTemplate.resolve(in, Map.of("cluster.name", "coding-cluster"));

        assertThat(out).isEqualTo(Map.of("prometheus", Map.of("prometheusSpec",
                Map.of("externalLabels", Map.of("cluster_name", "coding-cluster")))));
    }

    @Test
    void replacesInsideLongerStringsAndLists() {
        Map<String, Object> in = Map.of(
                "remoteWrite", List.of(Map.of("url", "https://${cluster.name}.example/api/v1/receive")),
                "note", "cluster=${cluster.name}");

        Object out = ValueTemplate.resolve(in, Map.of("cluster.name", "c1"));

        assertThat(out).isEqualTo(Map.of(
                "remoteWrite", List.of(Map.of("url", "https://c1.example/api/v1/receive")),
                "note", "cluster=c1"));
    }

    @Test
    void anUnknownPlaceholderFailsLoudly() {
        assertThatThrownBy(() -> ValueTemplate.resolve(Map.of("a", "${nope}"), Map.of()))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void anEmptyUrlPlaceholderFailsLoudly() {
        // 空 remoteWrite url 装出来的 Prometheus 永远上报不了，而且没有任何运行时报错
        assertThatThrownBy(() -> ValueTemplate.resolve(
                Map.of("remoteWrite", List.of(Map.of("url", "${thanos.receive.url}"))),
                Map.of("thanos.receive.url", "")))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("thanos.receive.url");
    }

    @Test
    void nonStringValuesPassThrough() {
        assertThat(ValueTemplate.resolve(Map.of("replicas", 3, "enabled", true), Map.of()))
                .isEqualTo(Map.of("replicas", 3, "enabled", true));
    }
}
