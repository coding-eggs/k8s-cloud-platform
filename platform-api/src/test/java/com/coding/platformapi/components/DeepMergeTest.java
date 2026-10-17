package com.coding.platformapi.components;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 深合并：上次快照 < 本次可编辑 < 契约值。
 * 契约是嵌套的（`prometheus.prometheusSpec.externalLabels.cluster_name`），浅合并会把整个
 * `prometheus` 子树替换掉。
 */
class DeepMergeTest {

    @Test
    void mergesNestedMapsInsteadOfReplacing() {
        Map<String, Object> base = Map.of("prometheus", Map.of("prometheusSpec",
                Map.of("externalLabels", Map.of("cluster_name", "old"), "replicas", 1)));
        Map<String, Object> overlay = Map.of("prometheus", Map.of("prometheusSpec",
                Map.of("replicas", 2)));

        Map<String, Object> out = DeepMerge.merge(base, overlay);

        assertThat(out).isEqualTo(Map.of("prometheus", Map.of("prometheusSpec",
                Map.of("externalLabels", Map.of("cluster_name", "old"), "replicas", 2))));
    }

    @Test
    void listsAreReplacedWholesale() {
        Map<String, Object> out = DeepMerge.merge(
                Map.of("args", List.of("--a", "--b")),
                Map.of("args", List.of("--c")));

        assertThat(out).isEqualTo(Map.of("args", List.of("--c")));
    }

    @Test
    void scalarOverwritesMapAndViceVersa() {
        assertThat(DeepMerge.merge(Map.of("x", Map.of("a", 1)), Map.of("x", "s")))
                .isEqualTo(Map.of("x", "s"));
        assertThat(DeepMerge.merge(Map.of("x", "s"), Map.of("x", Map.of("a", 1))))
                .isEqualTo(Map.of("x", Map.of("a", 1)));
    }
}
