package com.coding.platformapi.credential;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link CredentialProbeClient} 的 **HTTP 装配**：URL 拼出来对不对、Basic 头带没带、响应有没有喂给规则。
 *
 * <p>规则本身的用例全在 {@link CredentialProbeTest}（纯函数）。这里只验「发出去的东西对不对」——
 * 两半分开测，是因为混在一起时一次失败看不出是 URL 拼错了还是判定写错了。
 *
 * <p>后半段几条用**默认构造器**造的 {@code realClient} 打本地 {@code com.sun.net.httpserver}——
 * 那些是**传输层不变量**（重定向策略、地址格式归类），{@code MockRestServiceServer} 把它们整层替掉了，
 * 测不到。spec §5.2 的重定向要求正是因为「只有 prose 声称、没有测试钉住」而曾经没被实现。
 */
class CredentialProbeClientTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final CredentialProbeClient client = new CredentialProbeClient(builder.build());

    /** 真工厂（JDK HttpClient）—— 传输层不变量只能用它验 */
    private final CredentialProbeClient realClient = new CredentialProbeClient();

    @Test
    void gitHitsInfoRefsWithBasicAuthAndReadsBody() {
        server.expect(requestTo("https://g.example.com/r.git/info/refs?service=git-upload-pack"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic " + base64("ci-bot:tok")))
                .andRespond(withSuccess("001e# service=git-upload-pack\n0000", MediaType.TEXT_PLAIN));

        assertThat(client.probeGit("https://g.example.com/r.git", "ci-bot", "tok").ok()).isTrue();
        server.verify();
    }

    @Test
    void git401BecomesTheAmbiguousMessage() {
        server.expect(requestTo("https://g.example.com/r.git/info/refs?service=git-upload-pack"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        ProbeResult r = client.probeGit("https://g.example.com/r.git", "u", "p");

        assertThat(r.ok()).isFalse();
        assertThat(r.message()).doesNotContain("密码");
    }

    @Test
    void registryHitsV2() {
        // URL 由调用方给（各档的 probeSpec），本层不再自己拼 <endpoint>/v2/
        server.expect(requestTo("https://harbor.example.com/v2/"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(client.probeRegistry("https://harbor.example.com/v2/", "u", "p").ok()).isTrue();
    }

    @Test
    void anonymousProbeSendsNoAuthorizationHeader() {
        // 不带凭据时不能发 `Basic Og==` —— 一个只有冒号的空 Basic。有些对端会因此报 401，
        // 于是匿名连接永远测不过，而原因看起来像是"地址不对"。
        server.expect(requestTo("https://harbor.example.com/v2/"))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(client.probeRegistry("https://harbor.example.com/v2/", null, null).ok()).isTrue();
    }

    @Test
    void basicProbeStillSendsTheHeader() {
        server.expect(requestTo("https://harbor.example.com/v2/"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic " + base64("u:p")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(client.probeRegistry("https://harbor.example.com/v2/", "u", "p").ok()).isTrue();
    }

    @Test
    void redirectIsNotFollowedSoTheLocationReachesTheRules() throws Exception {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = httpServer.getAddress().getPort();
        httpServer.createContext("/", exchange -> {
            exchange.getResponseHeaders().add(HttpHeaders.LOCATION,
                    "http://127.0.0.1:" + port + "/moved.git/info/refs?service=git-upload-pack");
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
        });
        httpServer.start();
        try {
            // spec §5.2：重定向策略必须是 NEVER。跟随会让 Authorization 在跳转后被丢弃 →
            // 目标返 401 → 报「凭据不通过」—— 正是本模块最要防的假阴性（Review Focus #2 的伤害）；
            // 而且「把 Location 给用户一键回填」那条路径会变成死代码。
            ProbeResult r = realClient.probeGit("http://127.0.0.1:" + port + "/old.git", "u", "p");

            assertThat(r.ok()).isFalse();
            assertThat(r.message()).contains("跳转");
            assertThat(r.suggestedUrl()).isEqualTo("http://127.0.0.1:" + port + "/moved.git");
        } finally {
            httpServer.stop(0);
        }
    }

    @Test
    void malformedAddressIsReportedAsFormatErrorNotNetworkError() {
        // 地址里带空格 → URI.create 抛 IllegalArgumentException。那是**格式**问题，
        // 报成「网络不可达（请确认平台服务器能出站访问该地址）」会把管理员引去查网络，
        // 而且这句话会被写进 last_verify_error 长期显示在列表里。
        ProbeResult r = realClient.probeGit("http://exa mple.com/r.git", "u", "p");

        assertThat(r.ok()).isFalse();
        assertThat(r.message()).contains("地址格式");
        assertThat(r.message()).doesNotContain("网络不可达");
    }

    private static String base64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
