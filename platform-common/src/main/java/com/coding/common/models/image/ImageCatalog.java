package com.coding.common.models.image;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 从一份 values（chart 默认值或平台 values）里**找出所有镜像引用**。
 *
 * <p>与 {@link ImageRewriter} 用同一套口径（键名 + 值形状），所以「清单里列出来的」与
 * 「会被改写的」是同一批 —— 将来改规则只需要改一处。
 */
public final class ImageCatalog {

    private static final List<String> IMAGE_KEYS =
            List.of("image", "imageName", "imageRepository", "hub");

    private ImageCatalog() {
    }

    public static List<String> collect(Map<String, Object> values) {
        List<String> out = new ArrayList<>();
        walk(values, out);
        return out.stream().distinct().toList();
    }

    private static void walk(Object node, List<String> out) {
        if (node instanceof Map<?, ?> map) {
            Object repo = map.get("repository");
            if (repo instanceof String r && !r.isBlank()) {
                String registry = map.get("registry") instanceof String reg ? reg.trim() : "";
                String tag = map.get("tag") instanceof String t ? t.trim() : "";
                String full = (registry.isEmpty() ? "" : registry + "/") + r.trim()
                        + (tag.isEmpty() ? "" : ":" + tag);
                add(full, out);
            }
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (IMAGE_KEYS.contains(String.valueOf(e.getKey())) && e.getValue() instanceof String s) {
                    add(s, out);
                } else {
                    walk(e.getValue(), out);
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                walk(item, out);
            }
        }
    }

    private static void add(String candidate, List<String> out) {
        if (ImageReference.looksLikeImage(candidate)) {
            out.add(candidate.trim());
        }
    }
}
