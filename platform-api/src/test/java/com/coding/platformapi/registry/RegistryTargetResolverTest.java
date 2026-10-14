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
}
