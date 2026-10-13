package com.coding.platformapi.credential;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link CredentialProbeClient} 的 **HTTP 装配**：URL 拼出来对不对、Basic 头带没带、响应有没有喂给规则。
 *
 * <p>规则本身的用例全在 {@link CredentialProbeTest}（纯函数）。这里只验「发出去的东西对不对」——
 * 两半分开测，是因为混在一起时一次失败看不出是 URL 拼错了还是判定写错了。
 */
class CredentialProbeClientTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final CredentialProbeClient client = new CredentialProbeClient(builder.build());

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
        server.expect(requestTo("https://harbor.example.com/v2/"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(client.probeRegistry("harbor.example.com", "u", "p").ok()).isTrue();
    }

    private static String base64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
