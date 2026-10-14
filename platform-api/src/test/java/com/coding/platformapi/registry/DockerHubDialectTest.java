package com.coding.platformapi.registry;

import com.coding.platformapi.registry.BrowseFormat.TagInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DockerHubDialect} 的**纯规则**：输入切分、URL 构造、响应映射。
 *
 * <p>行为测试（出站那半）在 Task 6 补 —— 本类此刻只有不出站的部分。
 */
class DockerHubDialectTest {

    // ---- 输入切分 ----

    @Test
    void hostPrefixIsEmptyWhenUserTypedNoHost() {
        assertThat(DockerHubDialect.hostPrefixOf("ng")).isEmpty();
        assertThat(DockerHubDialect.hostPrefixOf("myteam/ng")).isEmpty();
    }

    @Test
    void hostPrefixKeepsWhicheverAliasTheUserTyped() {
        assertThat(DockerHubDialect.hostPrefixOf("docker.io/ng")).isEqualTo("docker.io/");
        assertThat(DockerHubDialect.hostPrefixOf("registry-1.docker.io/ng"))
                .isEqualTo("registry-1.docker.io/");
        assertThat(DockerHubDialect.hostPrefixOf("index.docker.io/ng")).isEqualTo("index.docker.io/");
    }

    @Test
    void hostPrefixIsRecognisedRegardlessOfCase() {
        assertThat(DockerHubDialect.hostPrefixOf("DOCKER.IO/ng")).isEqualTo("DOCKER.IO/");
    }

    @Test
    void singleSegmentFallsIntoTheOfficialLibrary() {
        // Docker 的字面语义：`nginx` 就是 library/nginx，与 `docker pull nginx` 一致
        assertThat(DockerHubDialect.namespaceOf("nginx")).isEqualTo("library");
        assertThat(DockerHubDialect.repoOf("nginx")).isEqualTo("nginx");
    }

    @Test
    void explicitNamespaceIsUsedAsIs() {
        assertThat(DockerHubDialect.namespaceOf("myteam/app")).isEqualTo("myteam");
        assertThat(DockerHubDialect.repoOf("myteam/app")).isEqualTo("app");
    }

    @Test
    void nestedPathsKeepEverythingAfterTheFirstSegment() {
        assertThat(DockerHubDialect.namespaceOf("myteam/sub/app")).isEqualTo("myteam");
        assertThat(DockerHubDialect.repoOf("myteam/sub/app")).isEqualTo("sub/app");
    }

    @Test
    void libraryPrefixIsStrippedOnlyWhenTheUserTypedNoHost() {
        assertThat(DockerHubDialect.stripLibrary("library/nginx", "")).isEqualTo("nginx");
        assertThat(DockerHubDialect.stripLibrary("library/nginx", "docker.io/"))
                .isEqualTo("library/nginx");
        assertThat(DockerHubDialect.stripLibrary("myteam/nginx", "")).isEqualTo("myteam/nginx");
    }

    // ---- URL 构造 ----

    @Test
    void searchUrlCarriesTheKeywordAndPageSize() {
        assertThat(DockerHubDialect.searchUrl("ng"))
                .isEqualTo("https://hub.docker.com/v2/search/repositories/?query=ng&page_size=25");
    }

    @Test
    void searchUrlEncodesTheKeyword() {
        // 关键词里有空格/中文时必须编码，否则 URI.create 会抛 —— 而补全的静默降级会把它吞掉，
        // 现象是「打中文/带空格的搜索没候选」，看不出任何线索
        String url = DockerHubDialect.searchUrl("my team");
        assertThat(url).doesNotContain(" ");
        assertThat(java.net.URI.create(url).getRawQuery()).contains("my%20team");
    }

    @Test
    void tagsUrlHasNoDoubleSlashBetweenNamespaceAndRepo() {
        assertThat(DockerHubDialect.tagsUrl("library", "nginx"))
                .isEqualTo("https://hub.docker.com/v2/repositories/library/nginx/tags?page_size=100");
    }

    @Test
    void repositoriesUrlEndsWithSlashBeforeTheQuery() {
        assertThat(DockerHubDialect.repositoriesUrl("library"))
                .isEqualTo("https://hub.docker.com/v2/repositories/library/?page_size=100");
    }

    @Test
    void theApiHostIsNotTheEndpoint() {
        // ⭐ 出站地址写死 hub.docker.com —— endpoint 是 docker.io，那是**匹配**用的，不是 API 站点
        assertThat(DockerHubDialect.repositoriesUrl("library")).startsWith("https://hub.docker.com/");
    }

    // ---- 响应映射 ----

    @Test
    void parsesSearchHitsWithoutTouchingTheNamespace() {
        String json = """
                {"count":2,"results":[
                  {"repo_name":"library/nginx"},
                  {"repo_name":"myteam/nginx-agent"}]}""";
        assertThat(DockerHubDialect.parseSearchHits(json))
                .containsExactly("library/nginx", "myteam/nginx-agent");
    }

    @Test
    void parsesRepositoryNamesFromTheBareNameField() {
        String json = """
                {"results":[{"name":"nginx"},{"name":"redis"}]}""";
        assertThat(DockerHubDialect.parseRepositoryNames(json)).containsExactly("nginx", "redis");
    }

    @Test
    void parsesTagsWithAllThreeDetailColumns() {
        String json = """
                {"results":[{"name":"1.27","last_updated":"2026-10-13T21:04:00Z",
                             "full_size":86400000,"digest":"sha256:9f2c"}]}""";
        List<TagInfo> tags = DockerHubDialect.parseTags(json);
        assertThat(tags).hasSize(1);
        assertThat(tags.get(0).name()).isEqualTo("1.27");
        assertThat(tags.get(0).pushedAt()).isEqualTo("2026-10-13T21:04:00Z");
        assertThat(tags.get(0).size()).isEqualTo(86400000L);
        assertThat(tags.get(0).digest()).isEqualTo("sha256:9f2c");
    }

    @Test
    void malformedJsonYieldsEmptyNotAnException() {
        // 站点接口没有 SLA（分析文档 §7.5 前提 2），响应形状随时可能变 ——
        // 不能让它把补全打挂。补全是每按键一次的高频动作。
        assertThat(DockerHubDialect.parseSearchHits("<html>gateway</html>")).isEmpty();
        assertThat(DockerHubDialect.parseTags("not json")).isEmpty();
        assertThat(DockerHubDialect.parseRepositoryNames("")).isEmpty();
    }

    @Test
    void missingResultsKeyYieldsEmpty() {
        assertThat(DockerHubDialect.parseSearchHits("{\"count\":0}")).isEmpty();
        assertThat(DockerHubDialect.parseTags("{}")).isEmpty();
    }

    @Test
    void nullFieldsAreSkippedNotTurnedIntoNulls() {
        String json = """
                {"results":[{"name":"nginx"},{"repo_name":null},{"name":null}]}""";
        assertThat(DockerHubDialect.parseRepositoryNames(json)).containsExactly("nginx");
    }
}
