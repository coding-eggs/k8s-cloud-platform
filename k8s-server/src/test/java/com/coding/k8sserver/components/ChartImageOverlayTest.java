package com.coding.k8sserver.components;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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

    /**
     * 子 chart 的镜像同样要改：helm 会把父 values 里同名的键合并进子 chart，
     * 所以 overlay 必须按「依赖名（或 alias）」挂上去。
     */
    @Test
    void overlayCoversSubchartValuesUnderTheDependencyKey(@TempDir Path dir) throws Exception {
        Path chart = dir.resolve("chart");
        Files.createDirectories(chart.resolve("charts/child"));
        Files.writeString(chart.resolve("Chart.yaml"), """
                apiVersion: v2
                name: parent
                version: 0.1.0
                dependencies:
                  - name: child
                    alias: kid
                """);
        Files.writeString(chart.resolve("values.yaml"), """
                image:
                  repository: quay.io/acme/parent
                  tag: "1.0"
                """);
        Files.writeString(chart.resolve("charts/child/Chart.yaml"), """
                apiVersion: v2
                name: child
                version: 0.1.0
                """);
        Files.writeString(chart.resolve("charts/child/values.yaml"), """
                image:
                  registry: docker.io
                  repository: bitnami/redis
                  tag: "7.2"
                """);

        Map<String, Object> overlay = ChartImageOverlay.of(chart, "harbor.ackcp.cloud", "platform");

        assertThat(overlay).containsKeys("image", "kid");
        @SuppressWarnings("unchecked")
        Map<String, Object> child = (Map<String, Object>) overlay.get("kid");
        @SuppressWarnings("unchecked")
        Map<String, Object> childImage = (Map<String, Object>) child.get("image");
        assertThat(childImage).containsEntry("registry", "harbor.ackcp.cloud/platform")
                .containsEntry("repository", "docker.io/bitnami/redis");
    }

    /**
     * seaweedfs 的拼法是 {@code registry + "/" + global.seaweedfs.image.name}：
     * registry 已经是 Harbor 前缀，name 就只能放**上游路径**。
     * 两边都带 Harbor 前缀会拼出 {@code harbor.ackcp.cloud/harbor.ackcp.cloud/docker.io/...}
     * （2026-10-29 审计发现：渲染出来是个根本不存在的路径）。
     */
    @Test
    void seaweedRegistryPinAndNamePinDoNotDoublePrefix() throws Exception {
        Map<String, Object> overlay = ChartImageOverlay.of(
                Path.of("src/main/resources/charts/components/seaweedfs/4.48.0/chart"),
                "harbor.ackcp.cloud", "platform");

        @SuppressWarnings("unchecked")
        Map<String, Object> image = (Map<String, Object>) overlay.get("image");
        assertThat(image).containsEntry("registry", "harbor.ackcp.cloud/platform");

        @SuppressWarnings("unchecked")
        Map<String, Object> global = (Map<String, Object>) overlay.get("global");
        @SuppressWarnings("unchecked")
        Map<String, Object> seaweedfs = (Map<String, Object>) global.get("seaweedfs");
        @SuppressWarnings("unchecked")
        Map<String, Object> name = (Map<String, Object>) seaweedfs.get("image");
        assertThat(name).containsEntry("name", "docker.io/chrislusf/seaweedfs");
    }

    /**
     * calico **有意不纳管**：它的组件镜像由 tigera-operator 运行时决定（chart 里没有），
     * 要换前缀只能写 {@code Installation.spec.registry}，而那会让 operator 一次性滚动重建
     * calico-node / typha / kube-controllers —— 单节点集群上 CNI 一断，新 Pod 起不来，
     * helm --wait 超时后平台回滚，改动被原样撤回（2026-10-29 实测，详见 {@code ChartImagePins}）。
     *
     * <p>这条断言是**绊线**：真要重新纳管，会先在这里红一次，提醒回去看那段原因。
     */
    @Test
    void calicoIsDeliberatelyNotMirrored() throws Exception {
        Map<String, Object> overlay = ChartImageOverlay.of(
                Path.of("src/main/resources/charts/components/tigera-operator/v3.33.0/chart"),
                "harbor.ackcp.cloud", "");

        assertThat(overlay).doesNotContainKey("installation");
    }

    /** 镜像在模板里拼死的两个 chart：靠一张钉死的表补上（表与模板字面量由护栏测试对齐）。 */
    @Test
    void pinnedDefaultsCoverTemplateAssembledImages() throws Exception {
        Map<String, Object> seaweed = ChartImageOverlay.of(
                Path.of("src/main/resources/charts/components/seaweedfs/4.48.0/chart"),
                "harbor.ackcp.cloud", "platform");
        assertThat(seaweed.toString()).contains("harbor.ackcp.cloud/platform");

        Map<String, Object> tekton = ChartImageOverlay.of(
                Path.of("src/main/resources/charts/components/tekton-operator/0.81.2/chart"),
                "harbor.ackcp.cloud", "platform");
        assertThat(tekton.toString())
                .contains("harbor.ackcp.cloud/platform/ghcr.io/tektoncd/operator/operator-");
    }
}
