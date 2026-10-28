package com.coding.k8sserver.components;

import com.coding.common.models.image.ImageRewriter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
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

    /** 读 {@code chartDir/values.yaml} 并生成 overlay；没有 values.yaml 就是空 overlay */
    public static Map<String, Object> of(Path chartDir, String host, String project) throws IOException {
        if (chartDir == null || host == null || host.isBlank()) {
            return Map.of();
        }
        Path values = chartDir.resolve("values.yaml");
        if (!Files.isRegularFile(values)) {
            return Map.of();
        }
        Map<String, Object> defaults = YAML.readValue(Files.readString(values),
                new TypeReference<Map<String, Object>>() { });
        return of(defaults, host, project);
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
