package com.coding.platformapi.outbound;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.platformapi.models.oauthclient.ClientAdminView;
import com.coding.platformapi.models.oauthclient.IdRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AuthClientAdminClientTest {

    private static final String BASE = "http://auth:9527/auth";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    @Test
    void emptyTokenRefusesToStart() {
        assertThatThrownBy(() -> new AuthClientAdminClient(RestClient.builder(), BASE, " "))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sendsInternalTokenAndUnwrapsData() {
        AuthClientAdminClient client = new AuthClientAdminClient(builder, BASE, "s3cret");
        server.expect(requestTo(BASE + "/internal/clients/list"))
                .andExpect(header(AuthClientAdminClient.HEADER, "s3cret"))
                .andRespond(withSuccess("{\"code\":200,\"msg\":\"ok\",\"data\":[{\"id\":\"a\",\"clientId\":\"ext-1\",\"builtin\":false}]}",
                        MediaType.APPLICATION_JSON));
        List<ClientAdminView> list = client.list();
        assertThat(list).extracting(ClientAdminView::getClientId).containsExactly("ext-1");
        server.verify();
    }

    @Test
    void businessErrorCodeIsPassedThrough() {
        AuthClientAdminClient client = new AuthClientAdminClient(builder, BASE, "s3cret");
        int locked = EnumResponseType.OAUTH_CLIENT_BUILTIN_LOCKED.getCode();
        server.expect(requestTo(BASE + "/internal/clients/delete"))
                .andRespond(withSuccess("{\"code\":" + locked + ",\"msg\":\"内置客户端不可删除\",\"data\":null}",
                        MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.delete(new IdRequest("x")))
                .isInstanceOf(CloudPlatformException.class)
                .satisfies(e -> {
                    assertThat(((CloudPlatformException) e).getCode()).isEqualTo(locked);
                    assertThat(((CloudPlatformException) e).getMsg()).isEqualTo("内置客户端不可删除");
                });
    }

    @Test
    void rejectedInternalTokenIsNotReportedAsUserLogout() {
        AuthClientAdminClient client = new AuthClientAdminClient(builder, BASE, "wrong");
        server.expect(requestTo(BASE + "/internal/clients/list"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":401,\"msg\":\"内部接口凭据无效\"}"));
        assertThatThrownBy(client::list)
                .isInstanceOf(CloudPlatformException.class)
                .satisfies(e -> assertThat(((CloudPlatformException) e).getCode())
                        .isEqualTo(EnumResponseType.ERROR.getCode()));
    }
}
