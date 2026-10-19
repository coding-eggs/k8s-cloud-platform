package com.coding.platformapi.metrics;

import com.coding.common.exception.CloudPlatformException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PromRulesClientTest {

    private static final String BASE = "http://prom.example:9090";

    private MockRestServiceServer server;
    private PromRulesClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new PromRulesClient(builder.build());
    }

    /** 正常响应 → 解析出 groups / rules / health / state / activeAlerts。 */
    @Test
    void parses_a_normal_rules_response() {
        server.expect(requestTo(BASE + "/api/v1/rules")).andRespond(withSuccess("""
                {"status":"success","data":{"groups":[
                  {"name":"app","file":"/etc/prometheus/rules/x/monitor-app-rules-abc.yaml","rules":[
                    {"name":"HighCPU","type":"alerting","health":"ok","state":"firing",
                     "alerts":[{"labels":{}},{"labels":{}}]}
                  ]}
                ]}}
                """, MediaType.APPLICATION_JSON));

        var groups = client.groups(BASE);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).name()).isEqualTo("app");
        assertThat(groups.get(0).file()).endsWith("monitor-app-rules-abc.yaml");
        assertThat(groups.get(0).rules()).hasSize(1);
        assertThat(groups.get(0).rules().get(0).name()).isEqualTo("HighCPU");
        assertThat(groups.get(0).rules().get(0).health()).isEqualTo("ok");
        assertThat(groups.get(0).rules().get(0).state()).isEqualTo("firing");
        assertThat(groups.get(0).rules().get(0).activeAlerts()).isEqualTo(2);
    }

    /** status != success → 抛业务异常（由调用方降级，见 Task 12）。 */
    @Test
    void a_failed_envelope_throws() {
        server.expect(requestTo(BASE + "/api/v1/rules")).andRespond(withSuccess(
                "{\"status\":\"error\",\"error\":\"bad_data: parse error\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.groups(BASE))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("parse error");
    }

    /** 畸形 JSON → 抛业务异常，不能冒泡 RuntimeException（调用方只 catch 前者会漏）。 */
    @Test
    void malformed_json_throws_a_business_exception() {
        server.expect(requestTo(BASE + "/api/v1/rules"))
                .andRespond(withSuccess("{\"status\":", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.groups(BASE)).isInstanceOf(CloudPlatformException.class);
    }

    /** 空响应体 → 抛业务异常。 */
    @Test
    void an_empty_body_throws() {
        server.expect(requestTo(BASE + "/api/v1/rules")).andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.groups(BASE)).isInstanceOf(CloudPlatformException.class);
    }

    /** HTTP 500 → 抛业务异常（不是 RuntimeException）。 */
    @Test
    void an_http_error_throws_a_business_exception() {
        server.expect(requestTo(BASE + "/api/v1/rules")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.groups(BASE)).isInstanceOf(CloudPlatformException.class);
    }

    /**
     * recording 规则没有 state / activeAlerts / alerts —— 必须容忍（不能 NPE），
     * 且归一后 state 与 activeAlerts 必须是 **null**，不能臆造成 "unknown" 或 0。
     */
    @Test
    void recording_rules_without_state_are_tolerated() {
        server.expect(requestTo(BASE + "/api/v1/rules")).andRespond(withSuccess("""
                {"status":"success","data":{"groups":[
                  {"name":"rec","file":"/x/ns-cr-abc.yaml","rules":[
                    {"name":"job:rate5m","type":"recording","health":"ok"}
                  ]}
                ]}}
                """, MediaType.APPLICATION_JSON));

        var rules = client.groups(BASE).get(0).rules();

        assertThat(rules.get(0).state()).isNull();
        assertThat(rules.get(0).activeAlerts()).isNull();
    }

    /**
     * recording 规则即便带了 {@code alerts: []}（某些 Prometheus 版本会），
     * {@code activeAlerts} 也必须是 null 而不是 0 —— 0 会在 UI 上显示成「没有活跃告警」，
     * 而 recording 规则根本没有告警这个概念。
     */
    @Test
    void recording_rules_never_report_active_alerts_even_with_an_empty_array() {
        server.expect(requestTo(BASE + "/api/v1/rules")).andRespond(withSuccess("""
                {"status":"success","data":{"groups":[
                  {"name":"rec","file":"/x/ns-cr-abc.yaml","rules":[
                    {"name":"job:rate5m","type":"recording","health":"ok","state":"inactive","alerts":[]}
                  ]}
                ]}}
                """, MediaType.APPLICATION_JSON));

        var r = client.groups(BASE).get(0).rules().get(0);

        assertThat(r.activeAlerts()).isNull();
        assertThat(r.state()).isNull();
    }

    /** data 缺失 / groups 为 null → 空列表，不是 NPE。 */
    @Test
    void a_missing_data_section_yields_an_empty_list() {
        server.expect(requestTo(BASE + "/api/v1/rules"))
                .andRespond(withSuccess("{\"status\":\"success\"}", MediaType.APPLICATION_JSON));

        assertThat(client.groups(BASE)).isEmpty();
    }

    /** 通知正文要的 alertname → query 索引；recording 规则不进（它不产生告警）。 */
    @Test
    void alert_expressions_are_indexed_by_alertname() {
        server.expect(requestTo(BASE + "/api/v1/rules")).andRespond(withSuccess("""
                {"status":"success","data":{"groups":[
                  {"name":"app","rules":[
                    {"name":"HighCPU","type":"alerting","query":"rate(x[5m]) > 1"},
                    {"name":"job:rate5m","type":"recording","query":"rate(y[5m])"}
                  ]}
                ]}}
                """, MediaType.APPLICATION_JSON));

        var m = client.alertExpressions(BASE);

        assertThat(m).containsEntry("HighCPU", "rate(x[5m]) > 1");
        assertThat(m).doesNotContainKey("job:rate5m");
    }
}
