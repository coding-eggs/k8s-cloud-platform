package com.coding.k8sserver.components;

import com.coding.common.models.image.ImageRewriter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 chart 自带的 {@code values.yaml} 里提取「镜像改动」，生成一个最小 overlay。
 *
 * <p><b>为什么需要它</b>：组件/中间件的镜像绝大多数来自 chart 的默认 values，platform-api 传下来的
 * values 里根本没有 image 字段（只有平台自己设的那几项）。而 chart 文件只随 k8s-server 分发，
 * 所以改写必须发生在 k8s-server 这一侧、helm 之前。
 *
 * <p><b>为什么是「差异 overlay」而不是整份默认值</b>：把 chart 默认值原样当 user values 传下去，
 * 会把未改动的字段也变成显式值，覆盖掉 chart 模板里对 null 的特殊处理。这里只输出真正变化的叶子路径。
 */
public final class ChartImageOverlay {

    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private ChartImageOverlay() {
    }

    /**
     * 读 chart 目录并生成 overlay：<b>顶层 values + 每一层子 chart 的 values + 模板写死的镜像</b>。
     *
     * <p>子 chart 那层不能漏：helm 只会用父 values 覆盖子 chart 的默认值，父 values 里没写的镜像
     * 仍然来自 {@code charts/<sub>/values.yaml}（grafana、kube-state-metrics、node-exporter 都在这里）。
     * 挂载的键取父 {@code Chart.yaml} 依赖里的 {@code alias}，没有 alias 才用依赖名/目录名。
     */
    public static Map<String, Object> of(Path chartDir, String host, String project) throws IOException {
        if (chartDir == null || host == null || host.isBlank()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        mergeInto(out, ownOverlay(chartDir, host, project));

        Path subcharts = chartDir.resolve("charts");
        if (Files.isDirectory(subcharts)) {
            Map<String, String> keys = dependencyKeys(chartDir);
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(subcharts, Files::isDirectory)) {
                for (Path sub : dirs) {
                    String name = chartName(sub);
                    String key = keys.getOrDefault(name, sub.getFileName().toString());
                    Map<String, Object> subOverlay = of(sub, host, project);
                    if (subOverlay.isEmpty()) {
                        continue;
                    }
                    Object existing = out.get(key);
                    out.put(key, existing instanceof Map<?, ?> e
                            ? merge(castMap(e), subOverlay) : subOverlay);
                }
            }
        }

        // 镜像只写在模板里的那几份 chart（tekton / seaweedfs），表在 ChartImagePins
        mergeInto(out, ChartImagePins.overlay(chartDir, host, project));
        return out;
    }

    /** 单个目录自己的 values.yaml → overlay */
    private static Map<String, Object> ownOverlay(Path chartDir, String host, String project) throws IOException {
        Path values = chartDir.resolve("values.yaml");
        if (!Files.isRegularFile(values)) {
            return Map.of();
        }
        String content = Files.readString(values);
        if (content.isBlank()) {
            return Map.of();   // 子 chart（如 crds）会有空的 values.yaml
        }
        Map<String, Object> defaults = YAML.readValue(content,
                new TypeReference<Map<String, Object>>() { });
        Map<String, Object> overlay = of(defaults, host, project);
        if (overlay.isEmpty() || !declaresInsecureImagesSwitch(defaults)) {
            return overlay;
        }
        // bitnami 系 chart 会校验「渲染出来的镜像还是不是自家的」，我们一改写必然触发，
        // 校验失败会让 helm 直接渲染不出来（rabbitmq 就是栽在这）。镜像替换本来就是本意，关掉它。
        Map<String, Object> withSwitch = new LinkedHashMap<>(overlay);
        Map<String, Object> global = withSwitch.get("global") instanceof Map<?, ?> g
                ? castMap(g) : new LinkedHashMap<>();
        withSwitch.put("global", merge(global, Map.of("security", Map.of("allowInsecureImages", true))));
        return withSwitch;
    }

    @SuppressWarnings("unchecked")
    private static void mergeInto(Map<String, Object> target, Map<String, Object> overlay) {
        if (overlay == null || overlay.isEmpty()) {
            return;
        }
        Map<String, Object> merged = merge(target, overlay);
        target.clear();
        target.putAll(merged);
    }

    /** 父 Chart.yaml 的依赖：子 chart 名 → values 里该用的键（有 alias 用 alias） */
    private static Map<String, String> dependencyKeys(Path chartDir) throws IOException {
        Path chartYaml = chartDir.resolve("Chart.yaml");
        if (!Files.isRegularFile(chartYaml)) {
            return Map.of();
        }
        Map<String, Object> chart = YAML.readValue(Files.readString(chartYaml),
                new TypeReference<Map<String, Object>>() { });
        Map<String, String> keys = new LinkedHashMap<>();
        if (chart.get("dependencies") instanceof List<?> deps) {
            for (Object dep : deps) {
                if (dep instanceof Map<?, ?> d && d.get("name") instanceof String name) {
                    Object alias = d.get("alias");
                    keys.put(name, alias instanceof String a && !a.isBlank() ? a : name);
                }
            }
        }
        return keys;
    }

    private static String chartName(Path chartDir) throws IOException {
        Path chartYaml = chartDir.resolve("Chart.yaml");
        if (Files.isRegularFile(chartYaml)) {
            Map<String, Object> chart = YAML.readValue(Files.readString(chartYaml),
                    new TypeReference<Map<String, Object>>() { });
            if (chart.get("name") instanceof String name && !name.isBlank()) {
                return name;
            }
        }
        return chartDir.getFileName().toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    /** chart 自己声明了「是否允许非标准镜像」这个开关（bitnami 系） */
    private static boolean declaresInsecureImagesSwitch(Map<String, Object> defaults) {
        return defaults.get("global") instanceof Map<?, ?> global
                && global.get("security") instanceof Map<?, ?> security
                && security.containsKey("allowInsecureImages");
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> of(Map<String, Object> defaults, String host, String project) {
        if (defaults == null || defaults.isEmpty()) {
            return Map.of();
        }
        Object rewritten = ImageRewriter.rewrite(defaults, host, project);
        if (!(rewritten instanceof Map<?, ?>)) {
            return Map.of();
        }
        Object diff = diff(defaults, rewritten);
        return diff instanceof Map<?, ?> d ? (Map<String, Object>) d : Map.of();
    }

    /** 只保留变化的部分：相等 → null；map 递归；list/叶子不相同就直接返回改写后的值 */
    static Object diff(Object original, Object rewritten) {
        if (original instanceof Map<?, ?> before && rewritten instanceof Map<?, ?> after) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : after.entrySet()) {
                Object changed = diff(before.get(e.getKey()), e.getValue());
                if (changed != null) {
                    out.put(String.valueOf(e.getKey()), changed);
                }
            }
            return out.isEmpty() ? null : out;
        }
        if (original instanceof List<?> && rewritten instanceof List<?>) {
            return original.equals(rewritten) ? null : rewritten;
        }
        return rewritten == null || rewritten.equals(original) ? null : rewritten;
    }

    /** 深度合并：{@code over} 优先（平台表单值应该覆盖镜像 overlay） */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> over) {
        Map<String, Object> out = new LinkedHashMap<>(base == null ? Map.of() : base);
        if (over == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : over.entrySet()) {
            Object existing = out.get(e.getKey());
            Object value = e.getValue();
            if (existing instanceof Map<?, ?> a && value instanceof Map<?, ?> b) {
                out.put(e.getKey(), merge((Map<String, Object>) a, (Map<String, Object>) b));
            } else {
                out.put(e.getKey(), value);
            }
        }
        return out;
    }
}
