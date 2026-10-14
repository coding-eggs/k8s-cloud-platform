package com.coding.platformapi.registry;

import java.util.Comparator;
import java.util.List;

/**
 * 把用户在输入框里打的整串，归到某一条已配的连接上。**纯函数、无 IO。**
 *
 * <p>设计见 docs/superpowers/specs/2026-10-14-registry-design.md §4.1。三条硬边界都在这里：
 * <ol>
 *   <li><b>host 边界必须在 {@code /}、{@code :} 或串尾</b> —— 否则
 *       {@code harbor.example.com.evil.com} 会冒充 {@code harbor.example.com}；</li>
 *   <li><b>最长 endpoint 优先</b> —— 同 host 带端口与不带端口同时配上时，取更具体的；</li>
 *   <li><b>匹配不到一律返回 {@code null}</b> —— 调用方据此**不发任何请求**。</li>
 * </ol>
 *
 * <p>⚠️ 本类**只用来挑连接**。出站 URL 一律由被挑中的那条连接的 {@code endpoint} 拼 ——
 * <b>绝不拿用户输入里的 host 去构造目标地址</b>（那等于给调用方一个打任意地址的口子）。
 */
public final class RegistryTargetResolver {

    /** Docker 的保留判定：裸的 `localhost` 也算 registry host（本机 registry 常这么写）。 */
    private static final String LOCALHOST = "localhost";

    private RegistryTargetResolver() {
    }

    /**
     * 取 registry host。**不是每段首段都是 host** —— 镜像引用有两种写法：
     * <ul>
     *   <li>{@code harbor.example.com/proj/app} —— 首段是 host</li>
     *   <li>{@code myteam/app} —— 首段是 **Docker Hub 的命名空间**，不是 host</li>
     * </ul>
     *
     * <p>判据用 <b>Docker 自己的规则</b>（{@code docker pull} 就是这么分的）：
     * 首段含 {@code .} 或 {@code :}，或恰为 {@code localhost}，才算 registry host。
     * 单靠「第一个 {@code /} 之前」会把 {@code myteam/app} 判成 host {@code myteam} —— 那是错的。
     *
     * @return host；不是 host（隐式 Docker Hub 写法）时返回**空串**，调用方据此不发请求
     */
    public static String hostOf(String imagePrefix) {
        if (imagePrefix == null) {
            return "";
        }
        String s = imagePrefix.trim();
        int slash = s.indexOf('/');
        String head = slash >= 0 ? s.substring(0, slash) : s;
        // 不带斜杠的整串可能形如 host:1.2 —— 冒号后不是纯数字端口时才当 tag 剥。
        // （host:8443 也是同形，那是端口，不能剥。）
        int colon = head.lastIndexOf(':');
        if (colon > 0 && slash < 0 && !isPort(head.substring(colon + 1))) {
            head = head.substring(0, colon);
        }
        return isRegistryHost(head) ? head : "";
    }

    /**
     * 挑出该归哪条连接。取 {@code endpoint} 的**最长前缀匹配**；匹配不到返回 {@code null}。
     *
     * @param candidates 已启用、且 type 属于镜像仓库的连接
     */
    public static CredentialRef match(String imagePrefix, List<CredentialRef> candidates) {
        String host = hostOf(imagePrefix);
        if (host.isEmpty() || candidates == null || candidates.isEmpty()) {
            return null;
        }
        String full = imagePrefix.trim();
        return candidates.stream()
                .filter(c -> boundaryMatches(full, c.endpoint()))
                .max(Comparator.comparingInt(c -> c.endpoint().length()))
                .orElse(null);
    }

    /** {@code full} 以 {@code endpoint} 开头，且其后是 {@code /}、{@code :} 或串尾。 */
    private static boolean boundaryMatches(String full, String endpoint) {
        if (endpoint == null || endpoint.isEmpty() || !full.startsWith(endpoint)) {
            return false;
        }
        if (full.length() == endpoint.length()) {
            return true;
        }
        char next = full.charAt(endpoint.length());
        return next == '/' || next == ':';
    }

    /** Docker 的规则：含 {@code .} / {@code :}，或恰为 {@code localhost}。 */
    private static boolean isRegistryHost(String head) {
        return head.contains(".") || head.contains(":") || LOCALHOST.equals(head);
    }

    private static boolean isPort(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
