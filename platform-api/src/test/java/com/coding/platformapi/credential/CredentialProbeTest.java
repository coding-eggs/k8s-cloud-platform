package com.coding.platformapi.credential;

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
    void registryProbeUrlAddsSchemeAndStripsTrailingSlash() {
        assertThat(CredentialProbe.registryProbeUrl("harbor.example.com"))
                .isEqualTo("https://harbor.example.com/v2/");
        assertThat(CredentialProbe.registryProbeUrl("harbor.example.com/"))
                .isEqualTo("https://harbor.example.com/v2/");
        assertThat(CredentialProbe.registryProbeUrl("https://harbor.example.com/"))
                .isEqualTo("https://harbor.example.com/v2/");
        assertThat(CredentialProbe.registryProbeUrl("http://harbor.internal:5000"))
                .isEqualTo("http://harbor.internal:5000/v2/");
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

    @Test
    void registry200Passes() {
        assertThat(CredentialProbe.classifyRegistry(200).ok()).isTrue();
    }

    @Test
    void registry401NeverSaysPassword() {
        for (int status : new int[] {401, 403}) {
            ProbeResult r = CredentialProbe.classifyRegistry(status);
            assertThat(r.ok()).isFalse();
            assertThat(r.message()).contains("凭据");
            assertThat(r.message()).doesNotContain("密码");
        }
    }

    @Test
    void registry404BlamesTheAddress() {
        assertThat(CredentialProbe.classifyRegistry(404).message()).contains("registry");
    }
}
