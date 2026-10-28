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
                String withoutTag = (registry.isEmpty() ? "" : registry + "/") + r.trim();
                String full = withoutTag + (tag.isEmpty() ? "" : ":" + tag);
                add(full, out);
                if (tag.contains("{{")) {
                    add(withoutTag, out);   // tag 是模板串时按「不含 tag」也算一个
                }
            }
            // 模板拼装的三种写法（改写规则同款，见 ImageRewriter）
            pairStr(map.get("registry"), map.get("image"), true, out);
            pairStr(map.get("imageRegistry"), map.get("imageNamespace"), false, out);
            pairStr(map.get("defaultImageRegistry"), map.get("defaultImageRepository"), false, out);
            if (map.get("image") instanceof Map<?, ?> image
                    && image.get("name") instanceof String name && name.contains("/")
                    && !(image.get("repository") instanceof String nameRepo && !nameRepo.isBlank())) {
                add(name, out);   // seaweedfs：registry 与 name 分居两个 map
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

    /**
     * 两个字符串键拼一个镜像（registry + 仓库路径）。
     *
     * @param requireSlash 仓库那段必须带 {@code /}（{@code registry+image} 这种写法里 image 可能是单词名，
     *                     例如 Strimzi 的 {@code name: kafka}，那不是仓库路径）
     */
    private static void pairStr(Object registry, Object repository, boolean requireSlash, List<String> out) {
        if (registry instanceof String reg && !reg.isBlank() && !reg.contains("/")
                && repository instanceof String repo && !repo.isBlank()
                && (!requireSlash || repo.contains("/"))) {
            add(reg.trim() + "/" + repo.trim(), out);
        }
    }

    private static void add(String candidate, List<String> out) {
        if (ImageReference.looksLikeImage(candidate)) {
            out.add(candidate.trim());
        }
    }
}
