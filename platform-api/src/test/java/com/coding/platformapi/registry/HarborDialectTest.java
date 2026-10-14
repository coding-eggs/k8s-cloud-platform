package com.coding.platformapi.registry;

import com.coding.common.exception.CloudPlatformException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link HarborDialect} 的**纯规则**：输入切分、URL 构造、响应映射、面包屑。
 *
 * <p>后半段是**行为测试**（打 {@code MockRestServiceServer}）—— 它们才走得到
 * {@code suggest} / {@code browse} 真正发请求那条路径。**纯函数测得再密也覆盖不到它**：
 * 本类曾经 21 条全是纯函数，而 tag 补全把 tag 当成了仓库名、恒返回空，一条都没红。
 */
class HarborDialectTest {

    private static final CredentialRef CRED = new CredentialRef(
            "c1", "harbor-prod", "HARBOR", "harbor.example.com", "robot$ci", "pw");

    // ---- 输入切分 ----

    @Test
    void segmentsAfterHostAreSplitOnSlash() {
        assertThat(HarborDialect.segmentsOf("harbor.example.com/proj/app", "harbor.example.com"))
                .containsExactly("proj", "app");
        assertThat(HarborDialect.segmentsOf("harbor.example.com/proj", "harbor.example.com"))
                .containsExactly("proj");
        assertThat(HarborDialect.segmentsOf("harbor.example.com", "harbor.example.com"))
                .isEmpty();
    }

    @Test
    void tagSegmentIsDetectedByColon() {
        assertThat(HarborDialect.tagPrefixOf("harbor.example.com/proj/app:1.2")).isEqualTo("1.2");
        assertThat(HarborDialect.tagPrefixOf("harbor.example.com/proj/app")).isNull();
    }

    @Test
    void repositorySearchTermIsTheLastSegment() {
        // 用户在打「仓库名」时，最后一段就是搜索词；打了斜杠说明已进入仓库，不该再搜仓库
        assertThat(HarborDialect.searchTermOf("harbor.example.com/pro", "harbor.example.com")).isEqualTo("pro");
        assertThat(HarborDialect.searchTermOf("harbor.example.com", "harbor.example.com")).isNull();
    }

    // ---- URL 构造（只为断言形状，不真的发） ----

    @Test
    void searchUrlCarriesTheKeyword() {
        assertThat(HarborDialect.searchUrl(CRED, "pro"))
                .isEqualTo("https://harbor.example.com/api/v2.0/search?q=pro");
    }

    @Test
    void keywordIsUrlEncoded() {
        assertThat(HarborDialect.searchUrl(CRED, "a b/c"))
                .isEqualTo("https://harbor.example.com/api/v2.0/search?q=a%20b%2Fc");
    }

    @Test
    void artifactsUrlUsesProjectAndRepositoryPath() {
        assertThat(HarborDialect.artifactsUrl(CRED, "myproj", "team/app"))
                .isEqualTo("https://harbor.example.com/api/v2.0/projects/myproj/repositories/team%2Fapp/artifacts?page_size=100");
    }

    @Test
    void projectsUrlAndRepositoriesUrl() {
        assertThat(HarborDialect.projectsUrl(CRED))
                .isEqualTo("https://harbor.example.com/api/v2.0/projects?page_size=100");
        assertThat(HarborDialect.repositoriesUrl(CRED, "myproj"))
                .isEqualTo("https://harbor.example.com/api/v2.0/projects/myproj/repositories?page_size=100");
    }

    @Test
    void endpointWithSchemeIsNotDoubled() {
        CredentialRef withScheme = new CredentialRef(
                "c2", "h", "HARBOR", "https://harbor.example.com/", "u", "p");

        assertThat(HarborDialect.projectsUrl(withScheme))
                .isEqualTo("https://harbor.example.com/api/v2.0/projects?page_size=100");
    }

    // ---- 候选串（**完整字符串**，前端整段替换） ----

    @Test
    void repositoryCandidateIsTheFullReference() {
        assertThat(HarborDialect.repositoryCandidates(CRED, "myproj", List.of("team/app", "team/api")))
                .containsExactly("harbor.example.com/myproj/team/app",
                                 "harbor.example.com/myproj/team/api");
    }

    @Test
    void repositoryCandidateHandlesHarborsPrefixedName() {
        // ⚠️ Harbor 的 repositories[].name 回的是 **"project/repo" 全名**，不是裸 repo ——
        // 不剥掉 project 前缀就会拼出 harbor.example.com/myproj/myproj/team/app（多一段）。
        assertThat(HarborDialect.repositoryCandidates(CRED, "myproj",
                List.of("myproj/team/app")))
                .containsExactly("harbor.example.com/myproj/team/app");
    }

    @Test
    void tagCandidateIsTheFullReferenceWithTag() {
        assertThat(HarborDialect.tagCandidates(CRED, "myproj", "team/app", List.of("1.2.3", "1.2.4")))
                .containsExactly("harbor.example.com/myproj/team/app:1.2.3",
                                 "harbor.example.com/myproj/team/app:1.2.4");
    }

    // ---- 浏览 ----

    @Test
    void firstLevelListsProjectsWithBreadcrumbEmpty() {
        BrowseResult r = HarborDialect.mapProjects(CRED, List.of("myproj", "other"));

        assertThat(r.breadcrumb()).isEmpty();
        assertThat(r.children()).extracting(BrowseResult.Node::name).containsExactly("myproj", "other");
        assertThat(r.children().get(0).hasChildren()).isTrue();
        assertThat(r.children().get(0).copyText()).isNull();
    }

    @Test
    void secondLevelListsRepositoriesWithOneCrumb() {
        BrowseResult r = HarborDialect.mapRepositories(CRED, List.of("myproj"),
                List.of("team/app"));

        assertThat(r.breadcrumb()).hasSize(1);
        assertThat(r.breadcrumb().get(0).label()).isEqualTo("myproj");
        assertThat(r.breadcrumb().get(0).path()).containsExactly("myproj");
        assertThat(r.children().get(0).name()).isEqualTo("team/app");
        assertThat(r.children().get(0).hasChildren()).isTrue();
    }

    @Test
    void thirdLevelListsTagsWithCopyTextAndDetails() {
        HarborDialect.TagInfo t = new HarborDialect.TagInfo(
                "1.2.3", "2026-10-13T21:04:00Z", 86_400_000L, "sha256:abc");
        BrowseResult r = HarborDialect.mapArtifacts(CRED, List.of("myproj", "team/app"), List.of(t));

        BrowseResult.Node leaf = r.children().get(0);
        assertThat(leaf.name()).isEqualTo("1.2.3");
        assertThat(leaf.hasChildren()).isFalse();
        assertThat(leaf.copyText()).isEqualTo("harbor.example.com/myproj/team/app:1.2.3");
        // spec §5.2 / §12.1 要的是三列：推送时间 / 大小 / digest
        assertThat(leaf.details()).extracting(BrowseResult.Detail::label)
                .containsExactly("推送时间", "大小", "digest");
        assertThat(leaf.details().get(2).value()).isEqualTo("sha256:abc");
        // 面包屑两节：project → repo，且 path 是**累积**的（点击能跳回该层）
        assertThat(r.breadcrumb()).hasSize(2);
        assertThat(r.breadcrumb().get(1).path()).containsExactly("myproj", "team/app");
    }

    @Test
    void formatSizeIsHumanReadable() {
        assertThat(HarborDialect.formatSize(512)).isEqualTo("512 B");
        assertThat(HarborDialect.formatSize(86_400_000L)).isEqualTo("82.4 MB");
        assertThat(HarborDialect.formatSize(0)).isEqualTo("—");
        assertThat(HarborDialect.formatSize(-1)).isEqualTo("—");
    }

    @Test
    void artifactTagNameIsReadFromTheTagsArrayNotTheArtifactName() {
        // Harbor 的 artifact.name 是 digest（sha256:…），真正的人类可读 tag 在 tags[0].name
        assertThat(HarborDialect.tagNameOf(new String[]{"1.2.3"}, "sha256:abc")).isEqualTo("1.2.3");
        assertThat(HarborDialect.tagNameOf(new String[]{}, "sha256:abc")).isEqualTo("sha256:abc");
        assertThat(HarborDialect.tagNameOf(null, "sha256:abc")).isEqualTo("sha256:abc");
    }

    // ---- 响应映射（JSON → 中间形态），纯函数 ----

    @Test
    void parsesProjectNames() {
        String json = "[{\"name\":\"myproj\",\"repo_count\":3},{\"name\":\"other\"}]";
        assertThat(HarborDialect.parseProjectNames(json)).containsExactly("myproj", "other");
    }

    @Test
    void parsesRepositoryNamesStrippingTheProjectPrefix() {
        // ⚠️ Harbor 的 repositories[].name 回的是 **"project/repo" 全名**，不是裸 repo。
        // 剥离放在解析器里（而不是让调用方再走一遍），免得两处各剥一次对不上。
        String json = "[{\"name\":\"myproj/team/app\"},{\"name\":\"myproj/team/api\"}]";
        assertThat(HarborDialect.parseRepositoryNames(json, "myproj"))
                .containsExactly("team/app", "team/api");
    }

    @Test
    void parsesSearchHitsIntoRepositoriesOnly() {
        // /search 会同时回 projects 与 repositories；补仓库只取后者
        String json = "{\"project\":[{\"name\":\"myproj\"}],"
                + "\"repository\":[{\"repository_name\":\"team/app\"}]}";
        assertThat(HarborDialect.parseSearchHits(json)).containsExactly("team/app");
    }

    @Test
    void parsesArtifactsIntoTags() {
        String json = "[{\"name\":\"sha256:abc\",\"digest\":\"sha256:abc\",\"tags\":[{\"name\":\"1.2.3\"}],"
                + "\"push_time\":\"2026-10-13T21:04:00Z\",\"size\":86400000}]";
        assertThat(HarborDialect.parseArtifacts(json)).containsExactly(
                new HarborDialect.TagInfo("1.2.3", "2026-10-13T21:04:00Z", 86_400_000L, "sha256:abc"));
    }

    @Test
    void artifactDigestFallsBackToNameWhenAbsent() {
        // 真实 Harbor 的 artifact 对象字段是 `digest`；`name` 也常是同一个值。
        // 两者都没有时不该造出一个假 digest —— 用 null，UI 那边显示「—」。
        String json = "[{\"name\":\"sha256:xyz\",\"tags\":[{\"name\":\"1.0\"}],\"size\":1}]";
        assertThat(HarborDialect.parseArtifacts(json).get(0).digest()).isEqualTo("sha256:xyz");

        String bare = "[{\"tags\":[{\"name\":\"1.0\"}],\"size\":1}]";
        assertThat(HarborDialect.parseArtifacts(bare).get(0).digest()).isNull();
    }

    @Test
    void malformedJsonYieldsEmptyInsteadOfThrowing() {
        // 对端返回不属于我们的东西时不该把整个页面打挂
        assertThat(HarborDialect.parseProjectNames("<html>oops</html>")).isEmpty();
        assertThat(HarborDialect.parseRepositoryNames("null", "myproj")).isEmpty();
        assertThat(HarborDialect.parseArtifacts("{}")).isEmpty();
        assertThat(HarborDialect.parseSearchHits("not json")).isEmpty();
    }

    // ==================== 行为测试：走真正发请求那条路径 ====================
    //
    // ⚠️ 这一节是补的。本类此前 21 条**全是纯函数测试**，而 suggest / browse 的实现在纯函数之外 ——
    // 于是「tag 补全把 tag 当成仓库名、恒返回空」这个 bug 一条都没红（评审实测发现）。
    // 纯函数测得再密也覆盖不到「拼出来的 URL 长什么样、失败往哪走」。

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HarborDialect dialect = new HarborDialect(new RegistryHttpClient(builder.build()));

    @Test
    void suggestForATagAsksForTheRepositoryNotTheTag() {
        // ⚠️ 关键断言在 URL 上：仓库名必须是 `app`，不能是 `app:1`。
        // 拼成 `app:1` 的话真实 Harbor 回 404 → 补全静默恒空。
        server.expect(requestTo(
                        "https://harbor.example.com/api/v2.0/projects/proj/repositories/app/artifacts?page_size=100"))
                .andRespond(withSuccess(
                        "[{\"name\":\"sha256:abc\",\"tags\":[{\"name\":\"1.2\"}]}]", APPLICATION_JSON));

        assertThat(dialect.suggest(CRED, "harbor.example.com/proj/app:1"))
                .containsExactly("harbor.example.com/proj/app:1.2");
        server.verify();
    }

    @Test
    void suggestForANestedRepositoryKeepsTheWholePath() {
        // 仓库名里的 `/` 在路径上是 URL 编码的（Harbor 的惯例，同 artifactsUrlUsesProjectAndRepositoryPath）
        server.expect(requestTo(
                        "https://harbor.example.com/api/v2.0/projects/proj/repositories/team%2Fapp/artifacts?page_size=100"))
                .andRespond(withSuccess(
                        "[{\"name\":\"sha256:abc\",\"tags\":[{\"name\":\"2.0\"}]}]", APPLICATION_JSON));

        assertThat(dialect.suggest(CRED, "harbor.example.com/proj/team/app:2"))
                .containsExactly("harbor.example.com/proj/team/app:2.0");
        server.verify();
    }

    @Test
    void suggestForARepositorySearchesByTheSingleSegment() {
        server.expect(requestTo("https://harbor.example.com/api/v2.0/search?q=pro"))
                .andRespond(withSuccess(
                        "{\"project\":[],\"repository\":[{\"repository_name\":\"proj/team/app\"}]}", APPLICATION_JSON));

        assertThat(dialect.suggest(CRED, "harbor.example.com/pro"))
                .containsExactly("harbor.example.com/proj/team/app");
        server.verify();
    }

    @Test
    void browseFirstLevelListsProjects() {
        server.expect(requestTo("https://harbor.example.com/api/v2.0/projects?page_size=100"))
                .andRespond(withSuccess("[{\"name\":\"myproj\"}]", APPLICATION_JSON));

        assertThat(dialect.browse(CRED, List.of()).children())
                .extracting(BrowseResult.Node::name).containsExactly("myproj");
        server.verify();
    }

    @Test
    void browseThirdLevelListsTagsWithCopyText() {
        server.expect(requestTo(
                        "https://harbor.example.com/api/v2.0/projects/proj/repositories/app/artifacts?page_size=100"))
                .andRespond(withSuccess(
                        "[{\"name\":\"sha256:abc\",\"digest\":\"sha256:abc\","
                      + "\"tags\":[{\"name\":\"1.2\"}],\"push_time\":\"2026-10-13T21:04:00Z\",\"size\":1024}]",
                        APPLICATION_JSON));

        BrowseResult r = dialect.browse(CRED, List.of("proj", "app"));

        assertThat(r.children().get(0).copyText()).isEqualTo("harbor.example.com/proj/app:1.2");
        server.verify();
    }

    /** spec §5.3 的姿态差异：补全**静默降级** */
    @Test
    void suggestSwallowsUpstreamFailure() {
        server.expect(requestTo("https://harbor.example.com/api/v2.0/search?q=pro"))
                .andRespond(withStatus(INTERNAL_SERVER_ERROR));

        assertThat(dialect.suggest(CRED, "harbor.example.com/pro")).isEmpty();
    }

    /** spec §5.3 的姿态差异：浏览**要报错** */
    @Test
    void browsePropagatesUpstreamFailure() {
        server.expect(requestTo("https://harbor.example.com/api/v2.0/projects?page_size=100"))
                .andRespond(withStatus(INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> dialect.browse(CRED, List.of()))
                .isInstanceOf(CloudPlatformException.class);
    }

    // ---- endpoint 归一化（回归：配了却补不出来，且无声） ----
    //
    // 匹配层（RegistryTargetResolver）早就归一化了，段切分这一层没有 ——
    // 于是 endpoint 填成 https://harbor.example.com 时，连接照样匹配得上、这里却取不到段，
    // 补全静默返回空，而浏览页一切正常。GIT 的 placeholder 正是 https://gitlab.example.com，
    // 切类型时极易残留。

    @Test
    void segmentsWorkWhenEndpointCarriesAScheme() {
        assertThat(HarborDialect.segmentsOf("harbor.example.com/proj/app", "https://harbor.example.com"))
                .containsExactly("proj", "app");
    }

    @Test
    void segmentsWorkWhenEndpointHasATrailingSlash() {
        assertThat(HarborDialect.segmentsOf("harbor.example.com/proj/app", "harbor.example.com/"))
                .containsExactly("proj", "app");
    }

    @Test
    void segmentsWorkWhenEndpointIsUpperCase() {
        assertThat(HarborDialect.segmentsOf("harbor.example.com/proj/app", "HARBOR.example.com"))
                .containsExactly("proj", "app");
    }

    @Test
    void segmentsWorkWhenTheUserTypedTheHostInUpperCase() {
        assertThat(HarborDialect.segmentsOf("HARBOR.example.com/proj/app", "harbor.example.com"))
                .containsExactly("proj", "app");
    }

    @Test
    void searchTermStillRejectsTheWrongHost() {
        // 归一化不能把「别人的 host」也放进来
        assertThat(HarborDialect.searchTermOf("other.example.com/ng", "harbor.example.com")).isNull();
        assertThat(HarborDialect.searchTermOf("harbor.example.com.evil.com/ng", "harbor.example.com"))
                .isNull();
    }
}
