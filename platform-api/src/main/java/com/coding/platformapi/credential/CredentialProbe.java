package com.coding.platformapi.credential;

/**
 * 测试连接的**纯规则**：URL 构造与响应判定。**不含 IO**，所以可单测。
 * 出站那一半在 {@link CredentialProbeClient}。
 *
 * <p>设计见 docs/superpowers/specs/2026-10-13-credential-management-design.md §5，
 * 每条规则的依据都在 §0 的实测表里。
 */
public final class CredentialProbe {

    /**
     * git 智能协议的 pkt-line 头。**200 的响应体必须以此开头才算通过**（spec §5.3）。
     *
     * <p>只看状态码会被一整类假阳性骗过：反向代理的登录页、SSO 跳转、captive portal
     * 都可能对 {@code info/refs} 返回 200 + HTML。
     */
    static final String GIT_SMART_PREFIX = "001e# service=git-upload-pack";

    private CredentialProbe() {
    }

    /**
     * 规范化 git 探测 URL：去尾部斜杠 + 补 scheme（缺省 https）+ 拼
     * {@code /info/refs?service=git-upload-pack}。
     *
     * <p><b>不猜 {@code .git} 后缀</b>（spec §5.2）：实测 GitLab 省略它是 301、gitee 是 200，
     * 各 host 不一致。补后缀是一种猜测，猜错在别的 host 上就是假阴性，且用户看不出为什么。
     *
     * <p><b>但补 scheme 是另一回事</b>：用户手输 host（`gitlab.example.com/g/r.git`）是常态，
     * 缺 scheme 会让 {@code URI.create} 抛 {@code IllegalArgumentException}，被出站层的
     * catch-all 误报成「网络不可达」——把纯格式问题说成网络问题。REGISTRY 那条一直补，
     * 这里对称处理。`host:port/...` 这种写法（自建 GitLab / 本地 Harbor 常见）也能一并救回。
     */
    public static String gitProbeUrl(String repoUrl) {
        return withScheme(stripTrailingSlashes(repoUrl)) + "/info/refs?service=git-upload-pack";
    }

    /**
     * 把 3xx 的 {@code Location} 还原成仓库地址（剥掉 {@code /info/refs…} 之后的全部内容），
     * 供「对端要求地址为 X」的提示与一键回填。
     */
    public static String repoUrlFromLocation(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        int idx = location.indexOf("/info/refs");
        return idx > 0 ? location.substring(0, idx) : location;
    }

    /**
     * git 探测的结果判定。
     *
     * <p><b>401 / 403 的文案必须二义</b>（spec §5.4）：实测「不存在的仓库」与「凭据错」
     * 返回同一个 401，所以分不清。写「密码错误」会把管理员引去改一个没问题的密码。
     */
    public static ProbeResult classifyGit(int status, String body, String location) {
        if (status == 200) {
            return body != null && body.startsWith(GIT_SMART_PREFIX)
                    ? ProbeResult.pass()
                    : ProbeResult.fail("对端返回了非 git 协议内容，地址可能指向了网关或登录页");
        }
        if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
            String repo = repoUrlFromLocation(location);
            return repo != null
                    ? ProbeResult.failWithUrl("对端要求跳转，请改用该地址", repo)
                    : ProbeResult.fail("对端返回了 " + status + " 跳转，但未给出目标地址");
        }
        if (status == 401 || status == 403) {
            return ProbeResult.fail("凭据不通过，或该凭据无权访问此仓库");
        }
        if (status == 404) {
            return ProbeResult.fail("地址不对或仓库不存在");
        }
        return ProbeResult.fail("对端返回 " + status);
    }

    /**
     * registry 探测的结果判定。
     *
     * @param anonymous 该连接是不是匿名的。**401 的读法随之不同**：
     *                  OCI 规范下任何 registry 的 {@code GET /v2/} 未认证时返 401 是**正常行为** ——
     *                  它是「这是个 registry 且它要认证」，不是「你的凭据错了」。
     *                  对匿名连接判失败的话，**每一条匿名连接一建出来就是红的**（spec §3.7）。
     *                  403 是真实的拒绝（IP 被封等），两种都报。
     */
    public static ProbeResult classifyRegistry(int status, boolean anonymous) {
        return classifyRegistry(status, anonymous, "地址不对：该地址不是 registry（/v2/ 不存在）");
    }

    /**
     * @param notFoundMessage 该档对 404 的解释（{@code ProbeSpec#notFoundMessage()}）。
     *                        **必须由各档给**：对 Harbor 是「这不是个 registry」，
     *                        对 Docker Hub 是「公共 API 被中间件改写了」——
     *                        同一句写死会把管理员引去查一个没问题的地址。
     */
    public static ProbeResult classifyRegistry(int status, boolean anonymous, String notFoundMessage) {
        if (status == 200) {
            return ProbeResult.pass();
        }
        if (status == 401) {
            return anonymous
                    ? ProbeResult.pass("可访问（该 registry 要求认证，匿名仍可拉取公开仓库）")
                    : ProbeResult.fail("凭据不通过，或该凭据无权访问此 registry");
        }
        if (status == 403) {
            return ProbeResult.fail(anonymous
                    ? "该 registry 拒绝了这个来源的匿名访问"
                    : "凭据不通过，或该凭据无权访问此 registry");
        }
        if (status == 404) {
            return ProbeResult.fail(notFoundMessage);
        }
        return ProbeResult.fail("对端返回 " + status);
    }

    private static String stripTrailingSlashes(String s) {
        String base = s == null ? "" : s.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    /** 缺 scheme 就补 https。两类探测统一走这里，避免只有一边补、另一边被误报成网络问题。 */
    private static String withScheme(String base) {
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            return "https://" + base;
        }
        return base;
    }
}
