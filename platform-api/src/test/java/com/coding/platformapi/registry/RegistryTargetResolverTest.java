package com.coding.platformapi.registry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把用户在输入框里打的整串，归到某一条已配的连接上。**纯函数，无 IO。**
 *
 * <p>本类钉住 spec 的三条硬边界（Review Focus 1/2/3）：host 边界、最长匹配、匹配不到返回 null
 * （调用方据此**不发请求**）。
 */
class RegistryTargetResolverTest {

    private static CredentialRef cred(String id, String endpoint) {
        return new CredentialRef(id, id, "HARBOR", endpoint, "u", "p");
    }

    private final List<CredentialRef> candidates = List.of(
            cred("h1", "harbor.example.com"),
            cred("h2", "harbor.example.com:8443"),
            cred("h3", "reg.internal"));

    // ---- host 提取 ----

    @Test
    void hostOfTakesThePartBeforeTheFirstSlash() {
        assertThat(RegistryTargetResolver.hostOf("harbor.example.com/proj/ap")).isEqualTo("harbor.example.com");
    }

    @Test
    void hostOfFallsBackToStrippingTheTag() {
        assertThat(RegistryTargetResolver.hostOf("harbor.example.com")).isEqualTo("harbor.example.com");
        assertThat(RegistryTargetResolver.hostOf("harbor.example.com:1.2")).isEqualTo("harbor.example.com");
    }

    @Test
    void hostOfIsEmptyForAHostlessReference() {
        // 隐式 Docker Hub 写法（nginx:1.27、myteam/app）—— 本批一律归不到任何连接
        assertThat(RegistryTargetResolver.hostOf("nginx:1.27")).isEmpty();
        assertThat(RegistryTargetResolver.hostOf("myteam/app")).isEmpty();
        assertThat(RegistryTargetResolver.hostOf("")).isEmpty();
        assertThat(RegistryTargetResolver.hostOf(null)).isEmpty();
    }

    @Test
    void localhostIsAHostEvenWithoutADotOrPort() {
        // Docker 自己的规则：首段含 `.` / `:` 或恰为 localhost 才算 registry host
        assertThat(RegistryTargetResolver.hostOf("localhost/x")).isEqualTo("localhost");
        assertThat(RegistryTargetResolver.hostOf("localhost:5000/x")).isEqualTo("localhost:5000");
    }

    // ---- 匹配 ----

    @Test
    void matchesByEndpointPrefix() {
        assertThat(RegistryTargetResolver.match("harbor.example.com/proj/ap", candidates).id()).isEqualTo("h1");
    }

    @Test
    void matchesAnEndpointWithAPort() {
        assertThat(RegistryTargetResolver.match("harbor.example.com:8443/proj", candidates).id()).isEqualTo("h2");
    }

    @Test
    void longestEndpointWins() {
        // 两条都能「前缀匹配」时，取更具体的那个
        List<CredentialRef> both = List.of(cred("short", "harbor.example.com"),
                                          cred("long", "harbor.example.com:8443"));
        assertThat(RegistryTargetResolver.match("harbor.example.com:8443/proj", both).id()).isEqualTo("long");
    }

    @Test
    void hostBoundaryIsEnforced() {
        // ⚠️ Review Focus 2：朴素的 startsWith 会让 evil.com 冒充 example.com
        assertThat(RegistryTargetResolver.match("harbor.example.com.evil.com/proj", candidates)).isNull();
        assertThat(RegistryTargetResolver.match("harbor.example.comX/proj", candidates)).isNull();
    }

    @Test
    void aBareEndpointMatchesItself() {
        // 用户只打了 host，还没打斜杠 —— 这时候就该开始给仓库候选
        assertThat(RegistryTargetResolver.match("harbor.example.com", candidates).id()).isEqualTo("h1");
    }

    @Test
    void unmatchedReturnsNullSoTheCallerSendsNoRequest() {
        // ⚠️ Review Focus 3：返回 null 是「不发请求」的信号，不是错误
        assertThat(RegistryTargetResolver.match("unknown.example.com/x", candidates)).isNull();
        assertThat(RegistryTargetResolver.match("nginx:1.27", candidates)).isNull();
        assertThat(RegistryTargetResolver.match("", candidates)).isNull();
    }

    @Test
    void emptyCandidatesNeverMatches() {
        assertThat(RegistryTargetResolver.match("harbor.example.com/x", List.of())).isNull();
    }

    @Test
    void endpointIsNormalizedBeforeMatching() {
        // ⚠️ 凭据表单的 endpoint 是**自由文本、无格式校验**，而 GIT 档的 placeholder 正是
        // `https://gitlab.example.com` —— 切类型时极易残留。这些写法都合法，补全必须照样工作。
        // （browse 走的是另一条路径、不受影响，所以这个坑的表现是「浏览正常、补全恒空且无声」。）
        assertThat(RegistryTargetResolver.match("harbor.example.com/proj",
                List.of(cred("x", "https://harbor.example.com"))).id()).isEqualTo("x");
        assertThat(RegistryTargetResolver.match("harbor.example.com/proj",
                List.of(cred("x", "https://harbor.example.com/"))).id()).isEqualTo("x");
        assertThat(RegistryTargetResolver.match("harbor.example.com/proj",
                List.of(cred("x", "HARBOR.example.com"))).id()).isEqualTo("x");
        assertThat(RegistryTargetResolver.match("harbor.example.com:8443/proj",
                List.of(cred("x", "http://HARBOR.example.com:8443/"))).id()).isEqualTo("x");
    }

    @Test
    void normalizationDoesNotWidenTheHostBoundary() {
        // 归一化只该去掉 scheme/尾斜杠/大小写，绝不能把 evil.com 放进来
        assertThat(RegistryTargetResolver.match("harbor.example.com.evil.com/proj",
                List.of(cred("x", "https://harbor.example.com/")))).isNull();
    }

    @Test
    void aPureNumericTagIsNotAHost() {
        // ⭐ `nginx:1` 是「想看 1.x 的人必然先打到的一串」。被当成 host 就会走
        // 「带 host 且匹配不上」→ **零请求、零候选**，而 `nginx:1.27` 正常 ——
        // 现象看起来像「tag 补全时灵时不灵」。
        assertThat(RegistryTargetResolver.hostOf("nginx:1")).isEmpty();
        assertThat(RegistryTargetResolver.hostOf("postgres:16")).isEmpty();
        assertThat(RegistryTargetResolver.hostOf("redis:7")).isEmpty();
        // 而真正的 host:port 不能剥
        assertThat(RegistryTargetResolver.hostOf("harbor.example.com:8443"))
                .isEqualTo("harbor.example.com:8443");
        assertThat(RegistryTargetResolver.hostOf("localhost:5000")).isEqualTo("localhost:5000");
        // host:tag 仍按 tag 剥（既有行为，别改坏）
        assertThat(RegistryTargetResolver.hostOf("harbor.example.com:1.2"))
                .isEqualTo("harbor.example.com");
        // host:tag 且带路径
        assertThat(RegistryTargetResolver.hostOf("myteam/app:1")).isEmpty();
    }

    // ---- Docker Hub 的三个等效域名 ----

    @Test
    void allThreeDockerHubAliasesMatchTheSameConnection() {
        // 配了 Docker Hub 却因为写法不同补不出来，而补全是静默降级 —— 用户没有任何线索
        CredentialRef hub = cred("hub", "docker.io");
        List<CredentialRef> only = List.of(hub);

        assertThat(RegistryTargetResolver.match("docker.io/ng", only)).isEqualTo(hub);
        assertThat(RegistryTargetResolver.match("index.docker.io/ng", only)).isEqualTo(hub);
        assertThat(RegistryTargetResolver.match("registry-1.docker.io/ng", only)).isEqualTo(hub);
    }

    @Test
    void theAliasesAlsoWorkWhenTheConnectionIsConfiguredWithANonCanonicalOne() {
        CredentialRef hub = cred("hub", "registry-1.docker.io");
        assertThat(RegistryTargetResolver.match("docker.io/ng", List.of(hub))).isEqualTo(hub);
    }

    @Test
    void canonicalizingAliasesDoesNotBreakThePrefixBoundary() {
        // `docker.io.evil.com` 不能被当作 `docker.io`
        CredentialRef hub = cred("hub", "docker.io");
        assertThat(RegistryTargetResolver.match("docker.io.evil.com/ng", List.of(hub))).isNull();
        assertThat(RegistryTargetResolver.match("mydocker.io/ng", List.of(hub))).isNull();
    }

    @Test
    void canonicalizingAliasesDoesNotAffectOtherHosts() {
        // 别名表只该动那三个域名，别的一律按原样比
        assertThat(RegistryTargetResolver.match("reg.internal/app", candidates))
                .isEqualTo(cred("h3", "reg.internal"));
    }
}
