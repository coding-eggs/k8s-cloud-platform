package com.coding.common.models.image;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 chart values 里的镜像地址改写到平台 Harbor（values 层改写，不改 helm 渲染）。
 *
 * <p>只认 <b>键名</b>（{@code image} / {@code imageName} / {@code imageRepository} / {@code repository}），
 * 且值必须通过 {@link ImageReference#looksLikeImage} —— values 里的 {@code imagePullPolicy}、{@code tag}、
 * 路径、URL 一律不碰。
 *
 * <p>chart 里 image 有两种常见写法，都要覆盖：
 * <pre>
 *   image: quay.io/opstree/redis:v7.0.15                    ← 整串
 *   image: { registry: docker.io, repository: grafana/grafana, tag: 12.0.0 }
 * </pre>
 *
 * 第二种要拆开写回：{@code registry = <harbor>/<project>}、
 * {@code repository = <上游主机>/<仓库>}，渲染出来才是 {@code <harbor>/<project>/<上游主机>/<仓库>:<tag>}。
 *
 * <p>Istio 系 chart 用 {@code global.hub}（如 {@code docker.io/istio}）拼镜像，{@code hub} 按同样的规则改写。
 *
 * <p>返回**新的** Map/List（深拷贝），不改调用方持有的对象。
 */
public final class ImageRewriter {

    /** 值本身就是完整镜像引用的键 */
    private static final List<String> IMAGE_STRING_KEYS =
            List.of("image", "imageName", "imageRepository", "hub");

    private ImageRewriter() {
    }

    public static Object rewrite(Object node, String harborHost, String project) {
        if (harborHost == null || harborHost.isBlank() || project == null || project.isBlank()) {
            return node;
        }
        return rewriteNode(node, harborHost.trim(), project.trim());
    }

    private static Object rewriteNode(Object node, String host, String project) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), rewriteNode(e.getValue(), host, project));
            }
            for (String key : IMAGE_STRING_KEYS) {
                Object value = out.get(key);
                if (value instanceof String s && ImageReference.looksLikeImage(s)) {
                    out.put(key, ImageReference.parse(s).mirror(host, project));
                }
            }
            rewriteRepository(out, host, project);
            return out;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(rewriteNode(item, host, project));
            }
            return out;
        }
        return node;
    }

    /**
     * {@code repository} 单独出现（常与同层的 {@code registry} / {@code tag} 搭配）。
     *
     * <p>判定要用「拼起来的完整引用」：{@code repository: busybox} 单看是个单词（{@code looksLikeImage}
     * 为 false），加上 {@code tag: latest} 和 {@code registry: docker.io} 才认得出是镜像。
     */
    private static void rewriteRepository(Map<String, Object> map, String host, String project) {
        Object repo = map.get("repository");
        if (!(repo instanceof String r) || r.isBlank()) {
            return;
        }
        String registry = map.get("registry") instanceof String reg ? reg.trim() : "";
        String tag = map.get("tag") instanceof String t ? t.trim() : "";
        String withoutTag = registry.isEmpty() ? r.trim() : registry + "/" + r.trim();
        String full = tag.isEmpty() ? withoutTag : withoutTag + ":" + tag;
        if (!ImageReference.looksLikeImage(full)) {
            return;
        }
        String mirrored = ImageReference.parse(withoutTag).mirror(host, project);
        if (!registry.isEmpty()) {
            String prefix = host + "/" + project + "/";
            if (mirrored.startsWith(prefix)) {
                // registry 拆分写回：registry 指 Harbor 项目，repository 带上游主机名
                map.put("registry", host + "/" + project);
                map.put("repository", mirrored.substring(prefix.length()));
            }
            return;
        }
        map.put("repository", mirrored);
    }
}
