package com.coding.k8sserver.components;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * chart 默认 values → 最小镜像 overlay。
 *
 * <p>只输出真正变化的路径：整份默认值当 user values 传下去会覆盖模板里对 null 的处理。
 */
class ChartImageOverlayTest {

    @Test
    void overlayContainsOnlyTheChangedImagePath() {
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("repository", "registry.k8s.io/metrics-server/metrics-server");
        image.put("tag", "");
        image.put("pullPolicy", "IfNotPresent");
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("replicas", 1);
        defaults.put("image", image);

        Map<String, Object> overlay = ChartImageOverlay.of(defaults, "harbor.ackcp.cloud", "platform");

        assertThat(overlay).containsOnlyKeys("image");
        @SuppressWarnings("unchecked")
        Map<String, Object> imageOverlay = (Map<String, Object>) overlay.get("image");
        assertThat(imageOverlay).containsOnlyKeys("repository");
        assertThat(imageOverlay.get("repository"))
                .isEqualTo("harbor.ackcp.cloud/platform/registry.k8s.io/metrics-server/metrics-server");
    }

    @Test
    void overlayIsEmptyWhenNothingLooksLikeAnImage() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("imagePullPolicy", "IfNotPresent");
        defaults.put("path", "/var/tempo/traces");

        assertThat(ChartImageOverlay.of(defaults, "harbor.ackcp.cloud", "platform")).isEmpty();
    }

    @Test
    void platformValuesWinOverTheOverlay() {
        Map<String, Object> overlay = Map.of("image", Map.of("repository", "harbor/platform/a"));
        Map<String, Object> platform = Map.of("image", Map.of("tag", "9.5.0"), "replicas", 3);

        Map<String, Object> merged = ChartImageOverlay.merge(overlay, platform);

        assertThat(merged).containsEntry("replicas", 3);
        @SuppressWarnings("unchecked")
        Map<String, Object> image = (Map<String, Object>) merged.get("image");
        assertThat(image).containsEntry("repository", "harbor/platform/a").containsEntry("tag", "9.5.0");
    }

    @Test
    void diffSkipsUnchangedLists() {
        assertThat(ChartImageOverlay.diff(List.of("a"), List.of("a"))).isNull();
        assertThat(ChartImageOverlay.diff(List.of("a"), List.of("b"))).isEqualTo(List.of("b"));
    }
}
