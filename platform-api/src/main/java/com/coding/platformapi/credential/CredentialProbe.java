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
     * 规范化 git 探测 URL：去尾部斜杠 + 拼 {@code /info/refs?service=git-upload-pack}。
     *
     * <p><b>不猜 {@code .git} 后缀</b>（spec §5.2）：实测 GitLab 省略它是 301、gitee 是 200，
     * 各 host 不一致。补后缀是一种猜测，猜错在别的 host 上就是假阴性，且用户看不出为什么。
     */
    public static String gitProbeUrl(String repoUrl) {
        return stripTrailingSlashes(repoUrl) + "/info/refs?service=git-upload-pack";
    }

    /** 规范化 registry 探测 URL：补 scheme（缺省 https）+ 去尾斜杠 + {@code /v2/}。 */
    public static String registryProbeUrl(String endpoint) {
        String base = stripTrailingSlashes(endpoint);
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            base = "https://" + base;
        }
        return base + "/v2/";
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
     * registry 探测的结果判定。{@code /v2/} 是 OCI Distribution 规范的一部分，所有 registry 都实现，
     * 所以不需要额外的探测入参。
     */
    public static ProbeResult classifyRegistry(int status) {
        if (status == 200) {
            return ProbeResult.pass();
        }
        if (status == 401 || status == 403) {
            return ProbeResult.fail("凭据不通过，或该凭据无权访问此 registry");
        }
        if (status == 404) {
            return ProbeResult.fail("地址不对：该地址不是 registry（/v2/ 不存在）");
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
}
