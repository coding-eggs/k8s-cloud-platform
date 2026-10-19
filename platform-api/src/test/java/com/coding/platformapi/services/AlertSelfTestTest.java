package com.coding.platformapi.services;

import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.k8s.PlatformAlertEventMapper;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.platformapi.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AlertSelfTestTest {

    private PlatformAlertEventMapper mapper;
    private PlatformTenantNamespaceMapper allocs;
    private MockRestServiceServer server;
    private AlertService svc;

    @BeforeEach
    void setUp() {
        mapper = mock(PlatformAlertEventMapper.class);
        allocs = mock(PlatformTenantNamespaceMapper.class);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        svc = new AlertService(mapper, allocs, mock(AuthContext.class), mock(com.coding.platformapi.k8s.K8sClient.class), builder, "s3cret", 8081);
    }

    /** self-test 打自己的回环地址，带正确的 Bearer —— 验的是完整 HTTP + 鉴权 + 解析 + 落库链路。 */
    @Test
    void self_test_posts_to_its_own_webhook_with_the_configured_token() {
        server.expect(requestTo("http://127.0.0.1:8081/alert/webhook?clusterId=c1"))
                .andExpect(header("Authorization", "Bearer s3cret"))
                .andRespond(withSuccess("{\"data\":{\"accepted\":2}}", MediaType.APPLICATION_JSON));
        when(mapper.findIdsByAlertname("c1", "PlatformSelfTest")).thenReturn(List.of(7L, 8L));

        svc.selfTest("c1");

        server.verify();
    }

    /** 返回体里带 HTTP 状态、落库行数、生成的 id —— 用户要能一眼看出断在哪一层。 */
    @Test
    void self_test_reports_the_http_status_and_the_row_count() {
        server.expect(requestTo(startsWith("http://127.0.0.1:8081/alert/webhook")))
                .andRespond(withSuccess("{\"data\":{\"accepted\":2}}", MediaType.APPLICATION_JSON));
        when(mapper.findIdsByAlertname(anyString(), anyString())).thenReturn(List.of(7L, 8L));

        var r = svc.selfTest("c1");

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.ids()).containsExactly(7L, 8L);
        assertThat(r.error()).isNull();
    }

    /** 回环地址由 server.port 拼出来，**不需要额外配置项**。 */
    @Test
    void the_loopback_url_is_derived_from_server_port() {
        server.expect(requestTo("http://127.0.0.1:8081/alert/webhook?clusterId=c2"))
                .andRespond(withSuccess("{\"data\":{\"accepted\":1}}", MediaType.APPLICATION_JSON));
        when(mapper.findIdsByAlertname(anyString(), anyString())).thenReturn(List.of(1L));

        svc.selfTest("c2");

        server.verify();
    }

    /** 回环请求本身失败（端口没起、鉴权被拒）→ 不抛，把原因放进结果里。 */
    @Test
    void a_failed_loopback_request_is_reported_not_thrown() {
        server.expect(requestTo(startsWith("http://127.0.0.1:8081/alert/webhook")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        var r = svc.selfTest("c1");

        assertThat(r.status()).isEqualTo(401);
        assertThat(r.error()).isNull();       // 401 是"收到了响应"，不是异常
    }

    /**
     * self-test 造的是 firing + resolved 两条，且 **startsAt 不同** ——
     * 它们会落成两行（幂等键的另一半），正好把"同一 fingerprint 不同会话"这条也验了。
     */
    @Test
    void self_test_sends_a_firing_and_a_resolved_with_different_starts_at() {
        server.expect(requestTo(startsWith("http://127.0.0.1:8081/alert/webhook")))
                .andExpect(jsonPath("$.alerts", hasSize(2)))
                .andExpect(jsonPath("$.alerts[0].status", is("resolved")))
                .andExpect(jsonPath("$.alerts[1].status", is("firing")))
                .andExpect(request -> {
                    String body = ((java.io.ByteArrayOutputStream)
                            ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBody())
                            .toString(java.nio.charset.StandardCharsets.UTF_8);
                    var m = java.util.regex.Pattern.compile("\"startsAt\":\"([^\"]+)\"").matcher(body);
                    java.util.List<String> ts = new java.util.ArrayList<>();
                    while (m.find()) ts.add(m.group(1));
                    assertThat(ts).hasSize(2);
                    assertThat(ts.get(0)).isNotBlank();
                    assertThat(ts.get(0)).isNotEqualTo(ts.get(1));
                })
                .andRespond(withSuccess("{\"data\":{\"accepted\":2}}", MediaType.APPLICATION_JSON));
        when(mapper.findIdsByAlertname(anyString(), anyString())).thenReturn(List.of(1L, 2L));

        svc.selfTest("c1");

        server.verify();
    }

    /** 有命名空间分配时，自检告警带上它（否则管理员列表里归属显示不出来）。 */
    @Test
    void self_test_uses_a_real_namespace_when_one_exists() {
        PlatformTenantNamespace a = new PlatformTenantNamespace();
        a.setNamespace("mon-app");
        when(allocs.listByCluster("c1")).thenReturn(List.of(a));
        server.expect(requestTo(startsWith("http://127.0.0.1:8081/alert/webhook")))
                .andExpect(jsonPath("$.alerts[0].labels.namespace", is("mon-app")))
                .andRespond(withSuccess("{\"data\":{\"accepted\":2}}", MediaType.APPLICATION_JSON));

        svc.selfTest("c1");

        server.verify();
    }

    /** 删除：按 id 物理删（清测试数据用）；不存在不报错（幂等）。 */
    @Test
    void delete_by_id_is_idempotent() {
        when(mapper.deleteById(7L)).thenReturn(0);   // 已经没了

        svc.delete(7L);          // 不该抛

        verify(mapper).deleteById(7L);
    }
}
