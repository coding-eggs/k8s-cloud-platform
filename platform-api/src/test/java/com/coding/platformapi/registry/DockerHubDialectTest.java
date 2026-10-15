package com.coding.platformapi.registry;

import com.coding.platformapi.registry.BrowseFormat.TagInfo;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link DockerHubDialect} 的**纯规则**（前半段）与**行为**（后半段，打 {@code MockRestServiceServer}）。
 *
 * <p>两半分开的理由同 {@code HarborDialectTest}：纯函数测得再密也覆盖不到
 * 「拼出来的 URL 长什么样、请求有没有发出去」——S3.1 的 tag 补全恒不工作就是从那条缝里漏掉的。
 */
class DockerHubDialectTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final DockerHubDialect dialect =
            new DockerHubDialect(new RegistryHttpClient(builder.build()));

    /** 该档只允许匿名 —— 两字段都空就是它的常态。 */
    private static CredentialRef anonCred() {
        return new CredentialRef("c1", "hub", "DOCKERHUB", "docker.io", null, null);
    }


    /**
     * 一条**带凭据**的 Docker Hub 连接（私有仓库）。
     *
     * <p>⚠️ 当年（2026-10-14）这一档被裁定「只允许匿名」，2026-10-15 用户撤销：
     * 私有仓库要能搜、能浏览、能推。匿名那一份仍然由**内置档**提供（它不走凭据表）。
     */
    private static CredentialRef authedCred() {
        return new CredentialRef("c2", "dockerhub-release", "DOCKERHUB", "docker.io", "myuser", "pat-xyz");
    }

    /** 登录换 token 的响应（站点 API 的 {@code POST /v2/users/login/}）。 */
    private static final String LOGIN_OK = "{\"token\":\"jwt-abc\"}";

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

    // ==================== 行为（出站） ====================

    @Test
    void suggestOnASingleSegmentSearchesAndStripsTheLibraryPrefix() {
        server.expect(requestTo("https://hub.docker.com/v2/search/repositories/?query=ng&page_size=25"))
                .andRespond(withSuccess("""
                        {"results":[{"repo_name":"library/nginx"},{"repo_name":"myteam/nginx-agent"}]}""",
                        MediaType.APPLICATION_JSON));

        assertThat(dialect.suggest(anonCred(), "ng")).containsExactly("nginx", "myteam/nginx-agent");
        server.verify();
    }

    @Test
    void suggestKeepsTheHostPrefixTheUserTyped() {
        server.expect(requestTo("https://hub.docker.com/v2/search/repositories/?query=ng&page_size=25"))
                .andRespond(withSuccess("""
                        {"results":[{"repo_name":"library/nginx"}]}""", MediaType.APPLICATION_JSON));

        assertThat(dialect.suggest(anonCred(), "docker.io/ng")).containsExactly("docker.io/library/nginx");
    }

    @Test
    void suggestOnAHostOnlyInputMakesNoRequest() {
        // 只打了 host：把整个 registry 的候选拉出来既慢又没意义。
        // ⚠️ 没有 server.expect —— 真发了请求这里就会报错，这正是要钉的「零请求」。
        assertThat(dialect.suggest(anonCred(), "docker.io")).isEmpty();
        assertThat(dialect.suggest(anonCred(), "docker.io/")).isEmpty();
        assertThat(dialect.suggest(anonCred(), "registry-1.docker.io")).isEmpty();
    }

    @Test
    void tagCompletionParsesNamespaceAndRepoConsistentlyWithTheNonTagPath() {
        // ⭐ Review Focus #2：`nginx:1` 要查 library/nginx，`bitnami/app:1` 要查 bitnami/app。
        // 解析错就会把 tag 候选挂到别的仓库上，而现象只是「这个 tag 不存在」
        server.expect(requestTo("https://hub.docker.com/v2/repositories/library/nginx/tags?page_size=100"))
                .andRespond(withSuccess("""
                        {"results":[{"name":"1.27","last_updated":"2026-10-13T21:04:00Z",
                                     "full_size":86400000,"digest":"sha256:9f2c"},
                                    {"name":"1.26","full_size":1}]}""", MediaType.APPLICATION_JSON));
        assertThat(dialect.suggest(anonCred(), "nginx:1")).containsExactly("nginx:1.27", "nginx:1.26");

        server.reset();
        server.expect(requestTo("https://hub.docker.com/v2/repositories/bitnami/app/tags?page_size=100"))
                .andRespond(withSuccess("{\"results\":[{\"name\":\"1.0\",\"full_size\":1}]}",
                        MediaType.APPLICATION_JSON));
        assertThat(dialect.suggest(anonCred(), "bitnami/app:1")).containsExactly("bitnami/app:1.0");
    }

    @Test
    void tagCompletionOnABareColonStillQueriesTheRepoNotTheRepoWithAColon() {
        // `nginx:` 是「刚打下冒号」—— tagPrefix 是空串，但那个冒号必须先剥掉，
        // 否则会去请求 library/nginx%3A（404 → 静默空）
        server.expect(requestTo("https://hub.docker.com/v2/repositories/library/nginx/tags?page_size=100"))
                .andRespond(withSuccess("{\"results\":[{\"name\":\"1.27\",\"full_size\":1}]}",
                        MediaType.APPLICATION_JSON));
        assertThat(dialect.suggest(anonCred(), "nginx:")).containsExactly("nginx:1.27");
    }

    @Test
    void upstreamFailureDegradesSilently() {
        // spec §2.5 / S3.1 spec §5.3：补全是每按键一次的高频动作，弹错会掩盖真正的问题
        server.expect(requestTo("https://hub.docker.com/v2/search/repositories/?query=ng&page_size=25"))
                .andRespond(withSuccess("<html>gateway</html>", MediaType.TEXT_HTML));

        assertThat(dialect.suggest(anonCred(), "ng")).isEmpty();
    }

    @Test
    void browseFirstLevelIsOnlyTheOfficialLibrary() {
        // 该档只允许匿名 → 没有 username 可以列，所以第一层只有一个入口（spec §3.3）
        BrowseResult r = dialect.browse(anonCred(), List.of());
        assertThat(r.children()).extracting(BrowseResult.Node::name).containsExactly("library");
        assertThat(r.children().get(0).hasChildren()).isTrue();
    }

    @Test
    void browseSecondLevelListsRepositories() {
        server.expect(requestTo("https://hub.docker.com/v2/repositories/library/?page_size=100"))
                .andRespond(withSuccess("{\"results\":[{\"name\":\"nginx\"},{\"name\":\"redis\"}]}",
                        MediaType.APPLICATION_JSON));

        BrowseResult r = dialect.browse(anonCred(), List.of("library"));
        assertThat(r.children()).extracting(BrowseResult.Node::name).containsExactly("nginx", "redis");
        assertThat(r.breadcrumb()).extracting(BrowseResult.Crumb::label).containsExactly("library");
    }

    @Test
    void browseTagLeafCopyTextStripsLibraryToo() {
        server.expect(requestTo("https://hub.docker.com/v2/repositories/library/nginx/tags?page_size=100"))
                .andRespond(withSuccess("""
                        {"results":[{"name":"1.27","full_size":86400000,"digest":"sha256:9f2c"}]}""",
                        MediaType.APPLICATION_JSON));

        BrowseResult r = dialect.browse(anonCred(), List.of("library", "nginx"));
        assertThat(r.children().get(0).copyText()).isEqualTo("nginx:1.27");
        assertThat(r.children().get(0).details()).extracting(BrowseResult.Detail::label)
                .containsExactly("推送时间", "大小", "digest");
    }

    @Test
    void browseTagLeafKeepsTheNamespaceWhenItIsNotTheLibrary() {
        server.expect(requestTo("https://hub.docker.com/v2/repositories/bitnami/nginx/tags?page_size=100"))
                .andRespond(withSuccess("{\"results\":[{\"name\":\"1.27\",\"full_size\":1}]}",
                        MediaType.APPLICATION_JSON));

        BrowseResult r = dialect.browse(anonCred(), List.of("bitnami", "nginx"));
        assertThat(r.children().get(0).copyText()).isEqualTo("bitnami/nginx:1.27");
    }

    @Test
    void probeTargetsThePublicApiNotTheEndpoint() {
        // ⭐ 这条钉住「出站地址不来自 endpoint」：endpoint 是 docker.io，API 站是 hub.docker.com
        ProbeSpec spec = dialect.probeSpec(anonCred());
        assertThat(spec.url()).isEqualTo("https://hub.docker.com/v2/repositories/library/?page_size=100");
        assertThat(spec.notFoundMessage()).contains("Docker Hub");
    }

    // ---- 带凭据的那一份（私有仓库）：认证、只答我名下、token 复用 ----

    @Test
    void asACredentialTypeItOnlyAcceptsBasicAuth() {
        // 匿名那一份是**内置档**（不经本档的凭据校验）；凭据行的存在意义就是带认证。
        // 若这里放行 NONE，就会造出一个与内置档一模一样、候选完全重复的第二来源。
        assertThat(dialect.authTypes()).containsExactly("BASIC");
    }

    @Test
    void anAuthenticatedSourceLogsInAndOffersMyOwnNamespaceOnTheFirstLevel() {
        // 第一层：library（公共）+ 我自己的命名空间（含私有仓库）
        BrowseResult r = dialect.browse(authedCred(), List.of());
        assertThat(r.children()).extracting(BrowseResult.Node::name)
                .containsExactly("library", "myuser");
    }

    @Test
    void listingMyRepositoriesCarriesTheBearerTokenFetchedFromTheLoginEndpoint() {
        // ⭐ 站点 API **不认 Basic**（实测：带 Basic 的错误凭据照样 200，等于被当成匿名）——
        // 必须先换 token，之后每个请求都带 Bearer。
        server.expect(requestTo("https://hub.docker.com/v2/users/login/"))
                .andRespond(withSuccess(LOGIN_OK, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://hub.docker.com/v2/repositories/myuser/?page_size=100"))
                .andExpect(header("Authorization", "Bearer jwt-abc"))
                .andRespond(withSuccess(
                        "{\"results\":[{\"name\":\"app-private\"},{\"name\":\"web\"}]}",
                        MediaType.APPLICATION_JSON));

        BrowseResult r = dialect.browse(authedCred(), List.of("myuser"));

        assertThat(r.children()).extracting(BrowseResult.Node::name)
                .containsExactly("app-private", "web");
    }

    @Test
    void aPrivateReposTagsComeBackWithBearerToo() {
        server.expect(requestTo("https://hub.docker.com/v2/users/login/"))
                .andRespond(withSuccess(LOGIN_OK, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://hub.docker.com/v2/repositories/myuser/app-private/tags?page_size=100"))
                .andExpect(header("Authorization", "Bearer jwt-abc"))
                .andRespond(withSuccess("{\"results\":[{\"name\":\"1.2\",\"full_size\":10}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(dialect.suggest(authedCred(), "myuser/app-private:1."))
                .containsExactly("myuser/app-private:1.2");
    }

    @Test
    void theCredentialSourceOnlyAnswersForMyOwnNamespace() {
        // 别人的仓库（公开的）由**内置档**答；这一份只答"我名下的" —— 两个来源各答各的，
        // 用户看到的候选因此既不重复也不缺。
        assertThat(dialect.suggest(authedCred(), "bitnami/nginx:1.")).isEmpty();
    }

    @Test
    void aSingleSegmentSearchListsMyOwnRepositoriesMatchingThePrefix() {
        server.expect(requestTo("https://hub.docker.com/v2/users/login/"))
                .andRespond(withSuccess(LOGIN_OK, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://hub.docker.com/v2/repositories/myuser/?page_size=100"))
                .andRespond(withSuccess(
                        "{\"results\":[{\"name\":\"app-private\"},{\"name\":\"web\"}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(dialect.suggest(authedCred(), "ap")).containsExactly("myuser/app-private");
    }

    @Test
    void theTokenIsFetchedOnceAndReusedAcrossCalls() {
        // 站点 API 无 SLA，登录是额外的一次往返 —— 每次按键都登录既慢又容易被限流
        server.expect(requestTo("https://hub.docker.com/v2/users/login/"))
                .andRespond(withSuccess(LOGIN_OK, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://hub.docker.com/v2/repositories/myuser/app/tags?page_size=100"))
                .andRespond(withSuccess("{\"results\":[{\"name\":\"1.0\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://hub.docker.com/v2/repositories/myuser/app/tags?page_size=100"))
                .andRespond(withSuccess("{\"results\":[{\"name\":\"1.0\"}]}", MediaType.APPLICATION_JSON));

        dialect.suggest(authedCred(), "myuser/app:1.");
        dialect.suggest(authedCred(), "myuser/app:1.");

        server.verify();   // 只登录了一次（第二次没有 login 期望）
    }

    @Test
    void theProbeOfAnAuthenticatedSourceCarriesABearerAndProbesMyRepositories() {
        server.expect(requestTo("https://hub.docker.com/v2/users/login/"))
                .andRespond(withSuccess(LOGIN_OK, MediaType.APPLICATION_JSON));

        ProbeSpec spec = dialect.probeSpec(authedCred());

        assertThat(spec.url()).isEqualTo("https://hub.docker.com/v2/repositories/myuser/?page_size=1");
        assertThat(spec.authorizationHeader()).isEqualTo("Bearer jwt-abc");
    }

    @Test
    void anAnonymousSourceNeverLogsIn() {
        // 内置档那条路必须一格都不变（它没有凭据，也不该去登录）
        server.expect(requestTo("https://hub.docker.com/v2/repositories/library/?page_size=100"))
                .andRespond(withSuccess("{\"results\":[{\"name\":\"alpine\"}]}", MediaType.APPLICATION_JSON));

        BrowseResult r = dialect.browse(anonCred(), List.of("library"));

        assertThat(r.children()).extracting(BrowseResult.Node::name).containsExactly("alpine");
        assertThat(dialect.probeSpec(anonCred()).authorizationHeader()).isNull();
    }
}
