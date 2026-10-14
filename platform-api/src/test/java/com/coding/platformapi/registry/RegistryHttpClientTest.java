package com.coding.platformapi.registry;

import com.coding.common.exception.CloudPlatformException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 出站层的 **HTTP 装配**。规则不在这一层（在 {@link HarborDialect} 的纯函数里）。
 *
 * <p>后半段用**默认构造器**造的客户端打本地 {@code com.sun.net.httpserver} —— 那是**传输层不变量**
 * （重定向策略），{@code MockRestServiceServer} 把传输层整个替掉了，测不到。
 * S0.2 终审抓到的那个 Critical 正是因为「只有 prose 声称、没有测试钉住」。
 */
class RegistryHttpClientTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final RegistryHttpClient client = new RegistryHttpClient(builder.build());
    private final RegistryHttpClient realClient = new RegistryHttpClient();

    private static final String PROJECTS_URL =
            "https://harbor.example.com/api/v2.0/projects?page_size=100";

    private static final CredentialRef CRED = new CredentialRef(
            "c1", "harbor", "HARBOR", "harbor.example.com", "robot$ci", "pw");

    @Test
    void sendsBasicAuthAndReturnsBody() {
        server.expect(requestTo(PROJECTS_URL))
                .andExpect(header(AUTHORIZATION, "Basic " + Base64.getEncoder()
                        .encodeToString("robot$ci:pw".getBytes(StandardCharsets.UTF_8))))
                .andRespond(withSuccess("[{\"name\":\"myproj\"}]", APPLICATION_JSON));

        assertThat(client.get(CRED, PROJECTS_URL)).isEqualTo("[{\"name\":\"myproj\"}]");
    }

    @Test
    void unauthorizedIsReportedWithTheAmbiguousMessage() {
        server.expect(requestTo(PROJECTS_URL)).andRespond(withStatus(UNAUTHORIZED));

        assertThatThrownBy(() -> client.get(CRED, PROJECTS_URL))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("无权访问")
                .hasMessageNotContaining("密码");
    }

    @Test
    void serverErrorIsReportedWithTheStatusCode() {
        server.expect(requestTo(PROJECTS_URL)).andRespond(withStatus(INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.get(CRED, PROJECTS_URL))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("500");
    }

    @Test
    void malformedUrlIsReportedAsFormatErrorNotAsNetworkError() {
        // URI.create 抛 IllegalArgumentException —— 那是**地址格式**问题，不是网络问题。
        // S0.2 的教训：报成「网络不可达」会把管理员引去查平台出站策略。
        assertThatThrownBy(() -> client.get(CRED, "https://harbor.example.com/a b"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("地址格式");
    }

    @Test
    void redirectIsNotFollowed() throws Exception {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = httpServer.getAddress().getPort();
        httpServer.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + "/elsewhere");
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
        });
        httpServer.start();
        try {
            // ⚠️ 必须**真正**关掉重定向。SimpleClientHttpRequestFactory 没有关它的 API 且默认跟随；
            // 跟进去了就会丢 Authorization，然后把「凭据不通过」报给一个没问题的凭据。
            CredentialRef c = new CredentialRef("c", "c", "HARBOR",
                    "http://127.0.0.1:" + port, "u", "p");

            assertThatThrownBy(() -> realClient.get(c, "http://127.0.0.1:" + port + "/api/v2.0/projects"))
                    .isInstanceOf(CloudPlatformException.class)
                    .hasMessageContaining("301");
        } finally {
            httpServer.stop(0);
        }
    }
}
