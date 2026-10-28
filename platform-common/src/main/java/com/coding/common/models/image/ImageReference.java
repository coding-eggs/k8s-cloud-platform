package com.coding.common.models.image;

import java.util.Set;

/**
 * 一个容器镜像引用（{@code [registry/]repository[:tag|@digest]}）的解析与镜像仓库改写。
 *
 * <p>镜像来源纳管（2026-10-28）的核心规则，纯函数、可单测：
 * <pre>
 *   redis:7.2                      → &lt;harbor&gt;/&lt;project&gt;/docker.io/library/redis:7.2
 *   quay.io/opstree/redis:v7.0.15  → &lt;harbor&gt;/&lt;project&gt;/quay.io/opstree/redis:v7.0.15
 * </pre>
 *
 * <p>规则要点：
 * <ul>
 *   <li>没有主机名的引用按 {@code docker.io} 补全，单段仓库补 {@code library/}（与 docker 客户端一致）；</li>
 *   <li><b>保留上游主机名</b>作为路径的第一段，不同上游的同名仓库（redis）不会撞；</li>
 *   <li><b>幂等</b>：已经指向目标仓库的引用原样返回，重复改写不会叠加前缀。</li>
 * </ul>
 */
public record ImageReference(String registry, String repository, String tagOrDigest, String original) {

    /** 常见上游主机：只用于「看起来像镜像」的判断，不参与改写 */
    private static final Set<String> KNOWN_HOSTS = Set.of(
            "docker.io", "registry-1.docker.io", "quay.io", "ghcr.io",
            "registry.k8s.io", "docker.elastic.co", "gcr.io", "k8s.gcr.io");

    public static ImageReference parse(String image) {
        String s = image == null ? "" : image.trim();
        String tag = "";
        int at = s.indexOf('@');
        if (at >= 0) {
            tag = s.substring(at);
            s = s.substring(0, at);
        } else {
            int colon = s.lastIndexOf(':');
            // 「冒号在最后一个 / 之后」才是 tag：quay.io:5000/a 里的冒号是主机端口
            if (colon > s.lastIndexOf('/')) {
                tag = s.substring(colon);
                s = s.substring(0, colon);
            }
        }
        boolean explicitHost = hasExplicitHost(s);
        String registry;
        String repo;
        if (explicitHost) {
            int slash = s.indexOf('/');
            registry = s.substring(0, slash);
            repo = s.substring(slash + 1);
        } else {
            registry = "docker.io";
            repo = s;
        }
        if ("docker.io".equals(registry) && !repo.contains("/")) {
            repo = "library/" + repo;
        }
        return new ImageReference(registry, repo, tag, image == null ? "" : image.trim());
    }

    /**
     * 改写成本平台 Harbor 里的地址：{@code <harborHost>/<project>/<上游主机>/<仓库路径><tag|digest>}。
     *
     * @param harborHost 形如 {@code harbor.ackcp.cloud}（**不带 scheme**：镜像引用里没有 scheme）
     */
    public String mirror(String harborHost, String project) {
        if (harborHost == null || harborHost.isBlank() || registry.equalsIgnoreCase(harborHost)) {
            return original;   // 幂等：已经在我们自己的仓库里
        }
        return harborHost + "/" + project + "/" + registry + "/" + repository + tagOrDigest;
    }

    /**
     * 判断一个 values 里的字符串「像不像镜像引用」——给 values 递归改写做白名单。
     *
     * <p>要求：有 tag/digest、有显式主机名、或至少是多段路径；URL、绝对路径、单词（如
     * {@code IfNotPresent}）都排除。
     */
    public static boolean looksLikeImage(String value) {
        if (value == null) {
            return false;
        }
        String s = value.trim();
        if (s.isEmpty() || s.contains(" ") || s.contains("://")
                || s.startsWith("/") || s.startsWith(".") || s.startsWith("-")) {
            return false;
        }
        if (!s.matches("[A-Za-z0-9][A-Za-z0-9._/:@-]*")) {
            return false;
        }
        ImageReference ref = parse(s);
        boolean tagged = !ref.tagOrDigest().isEmpty();
        boolean multiSegment = s.contains("/");
        return tagged || hasExplicitHost(s) || multiSegment;
    }

    /** 第一段是不是主机名（含 . 或 : 或 localhost）——docker 的判定规则 */
    static boolean hasExplicitHost(String image) {
        int slash = image.indexOf('/');
        if (slash < 0) {
            return false;
        }
        String first = image.substring(0, slash);
        return first.contains(".") || first.contains(":") || "localhost".equals(first)
                || KNOWN_HOSTS.contains(first);
    }
}
