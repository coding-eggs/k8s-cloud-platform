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
 * <p>还有几种「镜像被拆成两个键、由模板拼起来」的写法，只改一个会拼出
 * {@code quay.io/harbor.ackcp.cloud/...} 这种拉不到的地址，必须成对改写：
 * <pre>
 *   {registry: quay.io, image: tigera/operator}                       ← tigera
 *   {imageRegistry: quay.io, imageNamespace: jetstack}                ← cert-manager
 *   {defaultImageRegistry: quay.io, defaultImageRepository: strimzi}  ← Strimzi（broker 也走它）
 * </pre>
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
        if (harborHost == null || harborHost.isBlank()) {
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
            // 组合写法先判：registry/repository 与 registry/image 都把「主机」和「路径」拆成了两个键，
            // 只改其中一个会拼出 `quay.io/harbor.ackcp.cloud/...` 这种拉不到的地址。
            boolean imagePair = rewriteRegistryAndImage(out, host, project);
            rewriteRepository(out, host, project);
            rewriteRegistryAndNamespace(out, "imageRegistry", "imageNamespace", host, project);
            rewriteRegistryAndNamespace(out, "defaultImageRegistry", "defaultImageRepository", host, project);
            for (String key : IMAGE_STRING_KEYS) {
                if ("image".equals(key) && (imagePair || hasMirroredRegistrySibling(out, host))) {
                    continue;   // 已被 registry+image 组合规则接管（image 里已经是带上游主机的路径）
                }
                Object value = out.get(key);
                if (value instanceof String s && ImageReference.looksLikeImage(s)) {
                    out.put(key, ImageReference.parse(s).mirror(host, project));
                }
            }
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
     * 兄弟键写法：{@code {registry: quay.io, image: tigera/operator}}，模板拼 {@code {{registry}}/{{image}}}。
     *
     * <p>改完是 {@code registry = <harbor>/<project>}、{@code image = <上游主机>/<仓库路径>}，
     * 拼出来正好是镜像前缀。返回 true 表示这一对已经处理过，单键规则不要再动 {@code image}。
     */
    private static boolean rewriteRegistryAndImage(Map<String, Object> map, String host, String project) {
        Object regObj = map.get("registry");
        Object imageObj = map.get("image");
        if (!(regObj instanceof String reg) || !(imageObj instanceof String image)) {
            return false;
        }
        String r = reg.trim();
        String i = image.trim();
        // 已经改写过（registry 里带 /）或不是「主机 + 多段路径」的形状，都不碰
        if (r.isEmpty() || r.contains("/") || i.isEmpty() || !i.contains("/")) {
            return false;
        }
        String prefix = ImageReference.prefix(host, project) + "/";
        String mirrored = ImageReference.parse(r + "/" + i).mirror(host, project);
        if (!mirrored.startsWith(prefix)) {
            return false;
        }
        map.put("registry", ImageReference.prefix(host, project));
        map.put("image", mirrored.substring(prefix.length()));
        return true;
    }

    /**
     * 同级 {@code registry} 已经是平台 Harbor 前缀（改写过的痕迹）—— 说明 {@code image} 里存的是
     * 「上游主机/路径」，不能再整体改写一次，否则会叠成两层前缀。
     */
    private static boolean hasMirroredRegistrySibling(Map<String, Object> map, String host) {
        Object registry = map.get("registry");
        return registry instanceof String s && s.startsWith(host + "/");
    }

    /**
     * 两个键拼一个镜像：cert-manager 的 {@code imageRegistry + imageNamespace + image.name}、
     * Strimzi 的 {@code defaultImageRegistry + defaultImageRepository + image.name}。
     *
     * <p>改完是 {@code imageRegistry = <harbor>/<project>}、{@code imageNamespace = <上游主机>/<命名空间>}。
     */
    private static void rewriteRegistryAndNamespace(Map<String, Object> map, String registryKey,
                                                    String namespaceKey, String host, String project) {
        Object regObj = map.get(registryKey);
        Object nsObj = map.get(namespaceKey);
        if (!(regObj instanceof String reg) || !(nsObj instanceof String ns)) {
            return;
        }
        String r = reg.trim();
        String n = ns.trim();
        if (r.isEmpty() || r.contains("/") || n.isEmpty()) {
            return;
        }
        String prefix = ImageReference.prefix(host, project) + "/";
        String mirrored = ImageReference.parse(r + "/" + n).mirror(host, project);
        if (!mirrored.startsWith(prefix)) {
            return;
        }
        map.put(registryKey, ImageReference.prefix(host, project));
        map.put(namespaceKey, mirrored.substring(prefix.length()));
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
        // tag 可能是模板串（grafana 的 `{{ .Chart.AppVersion }}-distroless`），这时按「不含 tag」判断；
        // 反过来 `repository: busybox` 这种单词也要靠 tag 才认得出是镜像。
        // 「不含 tag」这条路要求带主机名，避免把 `myorg/myrepo` 这类非镜像路径也改写掉。
        if (!ImageReference.looksLikeImage(full)
                && !(ImageReference.hasExplicitHost(withoutTag)
                        && ImageReference.looksLikeImage(withoutTag))) {
            return;
        }
        String mirrored = ImageReference.parse(withoutTag).mirror(host, project);
        if (!registry.isEmpty()) {
            String prefix = ImageReference.prefix(host, project) + "/";
            if (mirrored.startsWith(prefix)) {
                // registry 拆分写回：registry 指 Harbor 项目，repository 带上游主机名
                map.put("registry", ImageReference.prefix(host, project));
                map.put("repository", mirrored.substring(prefix.length()));
            }
            return;
        }
        map.put("repository", mirrored);
    }
}
