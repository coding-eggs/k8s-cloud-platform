package com.coding.platformapi.registry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HarborDialect} 的**纯规则**：输入切分、URL 构造、响应映射、面包屑。
 * 不含 IO 的每一条都是确定性断言；出站那一半在 Task 7 的客户端。
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
        HarborDialect.TagInfo t = new HarborDialect.TagInfo("1.2.3", "2026-10-13T21:04:00Z", 86_400_000L);
        BrowseResult r = HarborDialect.mapArtifacts(CRED, List.of("myproj", "team/app"), List.of(t));

        BrowseResult.Node leaf = r.children().get(0);
        assertThat(leaf.name()).isEqualTo("1.2.3");
        assertThat(leaf.hasChildren()).isFalse();
        assertThat(leaf.copyText()).isEqualTo("harbor.example.com/myproj/team/app:1.2.3");
        assertThat(leaf.details()).extracting(BrowseResult.Detail::label)
                .containsExactly("推送时间", "大小");
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
        String json = "[{\"name\":\"sha256:abc\",\"tags\":[{\"name\":\"1.2.3\"}],"
                + "\"push_time\":\"2026-10-13T21:04:00Z\",\"size\":86400000}]";
        assertThat(HarborDialect.parseArtifacts(json))
                .containsExactly(new HarborDialect.TagInfo("1.2.3", "2026-10-13T21:04:00Z", 86_400_000L));
    }

    @Test
    void malformedJsonYieldsEmptyInsteadOfThrowing() {
        // 对端返回不属于我们的东西时不该把整个页面打挂
        assertThat(HarborDialect.parseProjectNames("<html>oops</html>")).isEmpty();
        assertThat(HarborDialect.parseRepositoryNames("null", "myproj")).isEmpty();
        assertThat(HarborDialect.parseArtifacts("{}")).isEmpty();
        assertThat(HarborDialect.parseSearchHits("not json")).isEmpty();
    }
}
