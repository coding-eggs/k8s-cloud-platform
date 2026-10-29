package com.coding.k8sserver.components;

import com.coding.common.models.image.ImageReference;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 镜像**只存在于模板里**的那几份 chart 的兜底表。
 *
 * <p>绝大多数 chart 把镜像写在 values 里，{@link com.coding.common.models.image.ImageRewriter} 能认出来；
 * 但有两种写法认不出来：
 * <ul>
 *   <li><b>模板里写死仓库</b>：tekton-operator 的 {@code operator.image.repository} 默认是空串，
 *       真正的镜像名（带内容哈希）写在 {@code templates/_helpers.tpl} 里；</li>
 *   <li><b>镜像名与仓库前缀分居两个 map</b>：seaweedfs 的 {@code global.seaweedfs.image.name} 是
 *       {@code chrislusf/seaweedfs}，registry 却来自顶层的 {@code image.registry}。</li>
 * </ul>
 *
 * <p>这张表按 chart 的相对路径钉死「values 路径 → 上游镜像」，由
 * {@code ChartImageRenderGuardTest} 渲染复核：chart 升级导致模板里的字面量变了，护栏会红，
 * 那时回来改这张表。
 */
final class ChartImagePins {

    /** values 里的路径 ← 一个上游镜像（会被改写成平台 Harbor 地址） */
    private record ImagePin(String path, String upstreamImage) {
    }

    /** values 里的路径 ← 平台 Harbor 前缀（{@code <host>/<project>}），给「registry 单独一个键」的 chart 用 */
    private record RegistryPin(String path) {
    }

    /**
     * values 里的路径 ← 「上游主机/仓库路径」，**不带** Harbor 前缀。
     *
     * <p>给「registry 与 name 各占一个键、模板里 {@code registry + "/" + name} 拼」的 chart 用：
     * registry 那个键已经由 {@link RegistryPin} 给了 Harbor 前缀，name 再带一遍就会拼成
     * {@code harbor.ackcp.cloud/harbor.ackcp.cloud/docker.io/...}。
     */
    private record UpstreamPathPin(String path, String upstreamImage) {
    }

    /**
     * values 里的路径 ← {@code <harbor 前缀>/<上游主机>/}（**结尾带斜杠**）。
     *
     * <p>给「组件镜像由 operator 运行时决定、chart 里根本没有」的 chart 用：calico 的
     * {@code Installation.spec.registry} 就是这么个整体前缀，CRD 要求以 {@code /} 结尾，
     * 拼法是 {@code <registry><imagePath>/<imagePath>...}，前缀换了就全换。
     */
    private record UpstreamRegistryPin(String path, String upstreamHost) {
    }

    private static final Map<String, List<Object>> PINS = Map.of(
            "components/seaweedfs/4.48.0", List.of(
                    new RegistryPin("image.registry"),
                    new UpstreamPathPin("global.seaweedfs.image.name", "chrislusf/seaweedfs")),
            // calico：tigera-operator 按内置列表创建 calico-node 等，镜像不进 helm 渲染
            "components/tigera-operator/v3.33.0", List.of(
                    new UpstreamRegistryPin("installation.registry", "quay.io")),
            "components/tekton-operator/0.81.2", List.of(
                    new ImagePin("operator.image.repository",
                            "ghcr.io/tektoncd/operator/operator-303303c315a48490ba6517859ef65b77"),
                    new ImagePin("webhook.image.repository",
                            "ghcr.io/tektoncd/operator/webhook-f2bb711aa8f0c0892856a4cbf6d9ddd8")));

    private ChartImagePins() {
    }

    /** 这个 chart 需要补的 values overlay；没有就是空 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> overlay(Path chartDir, String host, String project) {
        List<Object> pins = PINS.get(chartKey(chartDir));
        if (pins == null || pins.isEmpty() || host == null || host.isBlank()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Object pin : pins) {
            if (pin instanceof RegistryPin registry) {
                put(out, registry.path(), ImageReference.prefix(host, project));
            } else if (pin instanceof ImagePin image) {
                put(out, image.path(), ImageReference.parse(image.upstreamImage()).mirror(host, project));
            } else if (pin instanceof UpstreamPathPin path) {
                ImageReference ref = ImageReference.parsePrefix(path.upstreamImage());
                put(out, path.path(), ref.registry() + "/" + ref.repository());
            } else if (pin instanceof UpstreamRegistryPin registry) {
                put(out, registry.path(),
                        ImageReference.prefix(host, project) + "/" + registry.upstreamHost() + "/");
            }
        }
        return out;
    }

    /** 这个 chart 需要补的上游镜像（镜像清单用，与 {@link #overlay} 同源） */
    static List<String> images(Path chartDir) {
        List<Object> pins = PINS.get(chartKey(chartDir));
        if (pins == null) {
            return List.of();
        }
        return pins.stream()
                .filter(ImagePin.class::isInstance)
                .map(ImagePin.class::cast)
                .map(ImagePin::upstreamImage)
                .toList();
    }

    /** {@code .../charts/components/seaweedfs/4.48.0/chart} → {@code components/seaweedfs/4.48.0} */
    private static String chartKey(Path chartDir) {
        Path version = chartDir.getParent();
        Path name = version == null ? null : version.getParent();
        Path kind = name == null ? null : name.getParent();
        if (kind == null) {
            return "";
        }
        return kind.getFileName() + "/" + name.getFileName() + "/" + version.getFileName();
    }

    /** 按点分路径写进嵌套 map */
    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> root, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> node = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = node.get(parts[i]);
            if (!(next instanceof Map<?, ?>)) {
                next = new LinkedHashMap<String, Object>();
                node.put(parts[i], next);
            }
            node = (Map<String, Object>) next;
        }
        node.put(parts[parts.length - 1], value);
    }
}
