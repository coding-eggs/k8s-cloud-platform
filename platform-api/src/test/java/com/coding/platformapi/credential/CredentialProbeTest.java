package com.coding.platformapi.credential;

import com.coding.platformapi.registry.CredentialRef;
import com.coding.platformapi.registry.HarborDialect;
import com.coding.platformapi.registry.ProbeSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CredentialProbe} 的纯规则。**不含 IO，所以这里的每一条都是确定性断言。**
 *
 * <p>本测试的用例全部来自 2026-10-13 的实测（spec §0）：
 * GitLab 少 `.git` 返回 301、gitee 返回 200；公共仓库带错凭据返回 401（不是 200）；
 * 不存在的仓库也是 401；200 的响应体以 git pkt-line 头开头。
 */
class CredentialProbeTest {

    private static final String PKT_LINE = "001e# service=git-upload-pack";

    // ---- URL 构造 ----

    @Test
    void gitProbeUrlStripsTrailingSlashes() {
        assertThat(CredentialProbe.gitProbeUrl("https://gitlab.example.com/g/r.git/"))
                .isEqualTo("https://gitlab.example.com/g/r.git/info/refs?service=git-upload-pack");
        assertThat(CredentialProbe.gitProbeUrl("https://gitlab.example.com/g/r.git"))
                .isEqualTo("https://gitlab.example.com/g/r.git/info/refs?service=git-upload-pack");
    }

    @Test
    void gitProbeUrlDoesNotGuessDotGitSuffix() {
        // spec §5.2：不猜后缀。补了的话在别的 host 上是假阴性，用户还看不出为什么。
        assertThat(CredentialProbe.gitProbeUrl("https://gitlab.example.com/g/r"))
                .isEqualTo("https://gitlab.example.com/g/r/info/refs?service=git-upload-pack");
    }

    @Test
    void gitProbeUrlAddsSchemeWhenMissing() {
        // 「不加工」指的是不猜 .git 后缀，不是拒绝补 scheme。用户手输 host 是常态，
        // 缺 scheme 会让 URI.create 抛 IllegalArgumentException → 被 catch-all 误报成「网络不可达」。
        assertThat(CredentialProbe.gitProbeUrl("gitlab.example.com/g/r.git"))
                .isEqualTo("https://gitlab.example.com/g/r.git/info/refs?service=git-upload-pack");
        // host:port 这种写法（自建 GitLab / 本地 Harbor 常见）也要能补
        assertThat(CredentialProbe.gitProbeUrl("gitlab.example.com:8080/g/r.git"))
                .isEqualTo("https://gitlab.example.com:8080/g/r.git/info/refs?service=git-upload-pack");
    }

    /**
     * registry 探测 URL 的拼法**属于方言**，所以它住在 {@code HarborDialect} 里 ——
     * Docker Hub 的 API 站点是 {@code hub.docker.com} 而不是它的 endpoint，
     * 「所有 registry 都响应 {@code <endpoint>/v2/}」这个假设对第二档就不成立了（spec §4）。
     */
    private static ProbeSpec harborProbe(String endpoint) {
        return new HarborDialect(null).probeSpec(
                new CredentialRef("c1", "h", "HARBOR", endpoint, null, null));
    }

    @Test
    void harborProbeUrlAddsSchemeAndStripsTrailingSlash() {
        assertThat(harborProbe("harbor.example.com").url())
                .isEqualTo("https://harbor.example.com/v2/");
        assertThat(harborProbe("harbor.example.com/").url())
                .isEqualTo("https://harbor.example.com/v2/");
        assertThat(harborProbe("https://harbor.example.com/").url())
                .isEqualTo("https://harbor.example.com/v2/");
        assertThat(harborProbe("http://harbor.internal:5000").url())
                .isEqualTo("http://harbor.internal:5000/v2/");
    }

    @Test
    void harborProbe404SaysTheAddressIsNotARegistry() {
        // 404 的含义是方言相关的：对 Harbor 是「这不是个 registry」，
        // 对 Docker Hub 是另一回事（Task 6）。写死一句会把管理员引去查一个没问题的地址。
        assertThat(harborProbe("harbor.example.com").notFoundMessage()).contains("不是 registry");
    }

    @Test
    void repoUrlFromLocationStripsProbePath() {
        assertThat(CredentialProbe.repoUrlFromLocation(
                "https://gitlab.com/g/r.git/info/refs?service=git-upload-pack"))
                .isEqualTo("https://gitlab.com/g/r.git");
        assertThat(CredentialProbe.repoUrlFromLocation(null)).isNull();
    }

    // ---- git 判定 ----

    @Test
    void git200WithSmartProtocolBodyPasses() {
        assertThat(CredentialProbe.classifyGit(200, PKT_LINE + "\n0000014e8a", null).ok()).isTrue();
    }

    @Test
    void git200WithHtmlBodyIsNotAPass() {
        // 反向代理登录页 / captive portal 的典型行为：200 + HTML。只判状态码会判成「通过」（spec §5.3）。
        ProbeResult r = CredentialProbe.classifyGit(200, "<!DOCTYPE html><html>login</html>", null);

        assertThat(r.ok()).isFalse();
        assertThat(r.message()).contains("非 git");
    }

    @Test
    void git3xxTellsUserTheAddressInsteadOfPassword() {
        // spec §5.2：3xx 多半是地址少了 .git，不是对端故障 —— 要给可操作的提示
        ProbeResult r = CredentialProbe.classifyGit(301, "",
                "https://gitlab.com/g/r.git/info/refs?service=git-upload-pack");

        assertThat(r.ok()).isFalse();
        assertThat(r.suggestedUrl()).isEqualTo("https://gitlab.com/g/r.git");
    }

    @Test
    void git401NeverSaysPassword() {
        // spec §5.4：401 分不清「凭据错」与「凭据对但无权访问此仓库」（实测：不存在的仓库也是 401）。
        // 文案里出现「密码」会把管理员引去改一个没问题的密码。
        for (int status : new int[] {401, 403}) {
            ProbeResult r = CredentialProbe.classifyGit(status, "", null);
            assertThat(r.ok()).isFalse();
            assertThat(r.message()).contains("无权访问");
            assertThat(r.message()).doesNotContain("密码");
        }
    }

    @Test
    void git404BlamesTheAddressNotTheCredential() {
        ProbeResult r = CredentialProbe.classifyGit(404, "", null);

        assertThat(r.ok()).isFalse();
        assertThat(r.message()).contains("地址");
        assertThat(r.message()).doesNotContain("密码");
    }

    @Test
    void git500IsReportedWithStatusCode() {
        assertThat(CredentialProbe.classifyGit(500, "", null).message()).contains("500");
    }

    // ---- registry 判定 ----
    //
    // ⚠️ 第二个参数是「这条连接是不是匿名的」：401 的读法随之不同（spec §3.7）。

    @Test
    void registry200Passes() {
        assertThat(CredentialProbe.classifyRegistry(200, false).ok()).isTrue();
    }

    @Test
    void registry401NeverSaysPassword() {
        for (int status : new int[] {401, 403}) {
            ProbeResult r = CredentialProbe.classifyRegistry(status, false);
            assertThat(r.ok()).isFalse();
            assertThat(r.message()).contains("凭据");
            assertThat(r.message()).doesNotContain("密码");
        }
    }

    @Test
    void registry404BlamesTheAddress() {
        assertThat(CredentialProbe.classifyRegistry(404, false).message()).contains("registry");
    }

    @Test
    void basicRegistryProbeStillFailsOnUnauthorized() {
        ProbeResult r = CredentialProbe.classifyRegistry(401, false);
        assertThat(r.ok()).isFalse();
        assertThat(r.message()).contains("凭据不通过");
    }

    @Test
    void anonymousRegistryProbeTreatsUnauthorizedAsReachable() {
        // OCI 规范：任何 registry 的 GET /v2/ 未认证时返 401 是**正常行为** ——
        // 它是「这是个 registry 且它要认证」，不是「你的凭据错了」。
        // 不这么判的话，每一条匿名连接一建出来就是红的。
        ProbeResult r = CredentialProbe.classifyRegistry(401, true);
        assertThat(r.ok()).isTrue();
    }

    @Test
    void anonymousRegistryProbeStillFailsOnForbidden() {
        // 403 是真实的拒绝（IP 被封等），匿名与 BASIC 一样要报 —— 别把「匿名宽松」放大成「什么码都过」
        assertThat(CredentialProbe.classifyRegistry(403, true).ok()).isFalse();
        assertThat(CredentialProbe.classifyRegistry(404, true).ok()).isFalse();
    }
}
