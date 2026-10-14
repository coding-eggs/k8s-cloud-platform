package com.coding.platformapi.registry;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Locale;

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
        // 不带斜杠的整串可能形如 host:port，也可能形如 repo:tag —— 两者同形，要分开：
        //   · `harbor.example.com:8443` / `localhost:5000` → 冒号前是 host 且冒号后是**纯数字端口** → 留着
        //   · `harbor.example.com:1.2`                    → 冒号前是 host 但冒号后不是端口 → 那是 tag，剥掉
        //   · **`nginx:1`**                               → 冒号前**不是 host** → 那是 tag，剥掉
        // ⚠️ 最后那条以前是错的：旧判据只看「冒号后是不是纯数字」，于是 `nginx:1` 的冒号被留下，
        // 紧接着 isRegistryHost 的 `contains(":")` 就把它当成了 host →
        // 上层判「带 host 且匹配不上」→ **零请求、零候选**。而 `nginx:1` 正是想看 1.x 的人
        // 必然先打到的一串（`nginx:1.27` 反而正常），现象像「tag 补全时灵时不灵」。
        int colon = head.lastIndexOf(':');
        if (colon > 0 && slash < 0) {
            String beforeColon = head.substring(0, colon);
            String afterColon = head.substring(colon + 1);
            if (!isRegistryHost(beforeColon) || !isPort(afterColon)) {
                head = beforeColon;
            }
        }
        return isRegistryHost(head) ? head : "";
    }

    /**
     * 挑出该归哪条连接。取 {@code endpoint} 的**最长前缀匹配**；匹配不到返回 {@code null}。
     *
     * <p>比较前两边都**归一化**（见 {@link #normalizeEndpoint}）—— 凭据表单的 endpoint 是自由文本、
     * 无格式校验，而 GIT 档的 placeholder 正是 {@code https://gitlab.example.com}，
     * 切类型时极易残留 `https://` / 尾斜杠 / 大写。不归一化的话 browse 照常工作、**补全恒空且无声**
     * （静默降级把它盖掉了），用户看不出任何线索。
     *
     * @param candidates 已启用、且 type 属于镜像仓库的连接
     */
    public static CredentialRef match(String imagePrefix, List<CredentialRef> candidates) {
        String host = canonical(hostOf(imagePrefix).toLowerCase(Locale.ROOT));
        if (host.isEmpty() || candidates == null || candidates.isEmpty()) {
            return null;
        }
        return candidates.stream()
                .filter(c -> boundaryMatches(host, canonicalEndpoint(c)))
                .max(Comparator.comparingInt(c -> canonicalEndpoint(c).length()))
                .orElse(null);
    }

    /**
     * 指向同一个 registry 的等价写法，统一成一个规范名再比较。
     *
     * <p>Docker Hub 有三个都在用的域名。不归一化的话，用户打 {@code registry-1.docker.io/ng}
     * 而连接配的是 {@code docker.io} 就匹配不上 —— 现象是「明明配了 Docker Hub 却补不出来」，
     * 而补全是静默降级，用户没有任何线索。
     *
     * <p>⚠️ <b>顺序要紧</b>：先 {@link #normalizeEndpoint}（去 scheme / 尾斜杠 / 小写）
     * <b>再</b>查本表。反过来 {@code https://docker.io} 会因为带着 scheme 而查不到。
     */
    private static final Map<String, String> ALIASES = Map.of(
            "index.docker.io", "docker.io",
            "registry-1.docker.io", "docker.io");

    /** 连接的 endpoint 归一到可比较的形态。 */
    private static String canonicalEndpoint(CredentialRef c) {
        return canonical(normalizeEndpoint(c.endpoint()));
    }

    private static String canonical(String hostOrEndpoint) {
        return ALIASES.getOrDefault(hostOrEndpoint, hostOrEndpoint);
    }

    /**
     * 把 endpoint 归一成 {@code host[:port]}：去 scheme、去尾斜杠、转小写。
     *
     * <p>与 {@code HarborDialect.base()} 的拼 URL 逻辑**口径一致** —— 那边也主动处理了 scheme 与尾斜杠，
     * 说明「带 scheme 的 endpoint」本来就是被当作合法输入接受、且确实能工作的（只是补全匹配不上）。
     */
    public static String normalizeEndpoint(String endpoint) {
        if (endpoint == null) {
            return "";
        }
        String e = endpoint.trim().replaceFirst("(?i)^https?://", "");
        while (e.endsWith("/")) {
            e = e.substring(0, e.length() - 1);
        }
        return e.toLowerCase(Locale.ROOT);
    }

    /**
     * {@code host} 以 {@code endpoint} 开头，且**边界**是串尾或 {@code :}。
     *
     * <p>两边都已是 {@code host[:port]} 形态（无路径），所以边界只需认 {@code :} ——
     * 「同 host 带端口/不带端口同时配上」由最长匹配挑更具体的那条。
     * 这条同时挡住 {@code harbor.example.com.evil.com}（下一个字符是 {@code .}）。
     */
    private static boolean boundaryMatches(String host, String endpoint) {
        if (endpoint.isEmpty() || !host.startsWith(endpoint)) {
            return false;
        }
        return host.length() == endpoint.length() || host.charAt(endpoint.length()) == ':';
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
