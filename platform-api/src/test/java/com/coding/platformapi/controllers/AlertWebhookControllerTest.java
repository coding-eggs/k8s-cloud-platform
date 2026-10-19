package com.coding.platformapi.controllers;

import com.coding.data.models.k8s.PlatformAlertEvent;
import com.coding.platformapi.configs.AlertWebhookTokenCheck;
import com.coding.platformapi.services.AlertDispatchService;
import com.coding.platformapi.services.AlertIngestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ⚠️ 这些测试的重点不是"能不能收"，而是**失败时的 HTTP 状态码**。
 * Alertmanager 只看状态码：该 401/400 的地方回了 200，告警就被它当成投递成功丢掉了。
 */
class AlertWebhookControllerTest {

    private static final String TOKEN = "s3cret";
    private static final String URL = "/alert/webhook";

    private AlertIngestService ingest;
    private AlertDispatchService dispatch;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ingest = mock(AlertIngestService.class);
        dispatch = mock(AlertDispatchService.class);
        AlertWebhookTokenCheck check = new AlertWebhookTokenCheck(TOKEN);
        mvc = MockMvcBuilders.standaloneSetup(new AlertWebhookController(ingest, check, dispatch)).build();
    }

    /** ingest 返回的"落库后的行"：控制器只用它的条数当 accepted。 */
    private static java.util.List<PlatformAlertEvent> oneStoredEvent() {
        PlatformAlertEvent e = new PlatformAlertEvent();
        e.setId(1L);
        return java.util.List.of(e);
    }

    /** 裸数组（手工 curl / 旧版自检）：控制器有意兼容，但**真实推送永远是信封**。 */
    private static final String ONE_ALERT = """
            [{"status":"firing",
              "labels":{"alertname":"HighCPU","namespace":"ns1","severity":"warning"},
              "annotations":{"summary":"CPU 高"},
              "startsAt":"2026-10-17T10:00:00Z",
              "fingerprint":"fp-1"}]
            """;

    /** 密钥对 → 200 + {"accepted": N}。 */
    @Test
    void a_valid_token_ingests_and_returns_200() throws Exception {
        when(ingest.ingest(anyString(), any())).thenReturn(oneStoredEvent());

        mvc.perform(post(URL).queryParam("clusterId", "c1")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(ONE_ALERT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(1));

        verify(ingest).ingest(eq("c1"), any());
    }

    /** 密钥错 → **真 401**（不是 200 + body 里的业务码）。 */
    @Test
    void a_wrong_token_is_401() throws Exception {
        mvc.perform(post(URL).queryParam("clusterId", "c1")
                        .header("Authorization", "Bearer nope")
                        .contentType(MediaType.APPLICATION_JSON).content(ONE_ALERT))
                .andExpect(status().isUnauthorized());

        verify(ingest, never()).ingest(anyString(), any());
    }

    /** 没带 Authorization → 401。 */
    @Test
    void a_missing_authorization_header_is_401() throws Exception {
        mvc.perform(post(URL).queryParam("clusterId", "c1")
                        .contentType(MediaType.APPLICATION_JSON).content(ONE_ALERT))
                .andExpect(status().isUnauthorized());

        verify(ingest, never()).ingest(anyString(), any());
    }

    /** 带 "Bearer " 前缀与不带都要认（Alertmanager 的 http_config.authorization 会加前缀）。 */
    @Test
    void the_bearer_prefix_is_accepted_with_or_without_it() throws Exception {
        when(ingest.ingest(anyString(), any())).thenReturn(oneStoredEvent());

        mvc.perform(post(URL).queryParam("clusterId", "c1")
                        .header("Authorization", TOKEN)          // ← 不带前缀
                        .contentType(MediaType.APPLICATION_JSON).content(ONE_ALERT))
                .andExpect(status().isOk());
    }

    /** 空数组 → 200 + accepted: 0（Alertmanager 有时会推空批次）。 */
    @Test
    void an_empty_array_is_accepted_with_zero() throws Exception {
        when(ingest.ingest(anyString(), any())).thenReturn(java.util.List.of());

        mvc.perform(post(URL).queryParam("clusterId", "c1")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(0));
    }

    /** 畸形 JSON → **400**，不是 200（否则配错 payload 形状会被无声吞掉）。 */
    @Test
    void malformed_json_is_400_not_200() throws Exception {
        mvc.perform(post(URL).queryParam("clusterId", "c1")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":"))
                .andExpect(status().isBadRequest());

        verify(ingest, never()).ingest(anyString(), any());
    }

    /** clusterId 缺失 → **400**（配错了要能看出来，不能默默归到某个默认集群）。 */
    @Test
    void a_missing_cluster_id_is_400() throws Exception {
        mvc.perform(post(URL)                                  // ← 不带 clusterId
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(ONE_ALERT))
                .andExpect(status().isBadRequest());

        verify(ingest, never()).ingest(anyString(), any());
    }

    /** 落库内部异常也要回 200 —— 重试不会让它变好，只会放大。 */
    @Test
    void an_internal_ingest_failure_still_returns_200() throws Exception {
        when(ingest.ingest(anyString(), any())).thenThrow(new RuntimeException("db down"));

        mvc.perform(post(URL).queryParam("clusterId", "c1")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(ONE_ALERT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(0));
    }

    /**
     * ⚠️ **Alertmanager 真正推的是信封对象**（`{version, groupKey, status, alerts:[...]}`），
     * 不是裸数组 —— 裸数组是 {@code GET /api/v2/alerts} 的**返回**形状，两回事。
     * 而且 alert 里的 {@code status} 是**字符串** "firing"，不是 {@code {state:...}}。
     * 曾经按裸数组 + 对象 status 建模 → 真实推送整包解析失败 → 回 400 →
     * Alertmanager 报 `clientError` / `AlertmanagerFailedToSendAlerts`。
     */
    private static final String REAL_ENVELOPE = """
            {"receiver":"monitor/platform-webhook/platform","status":"firing",
             "alerts":[{"status":"firing",
                        "labels":{"alertname":"HighCPU","namespace":"ns1","severity":"warning"},
                        "annotations":{"summary":"CPU 高"},
                        "startsAt":"2026-10-19T02:21:40.088Z",
                        "endsAt":"0001-01-01T00:00:00Z",
                        "generatorURL":"http://am/graph",
                        "fingerprint":"91286ffa74e3a0eb"}],
             "groupLabels":{"alertname":"HighCPU"},
             "commonLabels":{"alertname":"HighCPU"},
             "commonAnnotations":{"summary":"CPU 高"},
             "externalURL":"http://am:9093","version":"4",
             "groupKey":"{}:{alertname=\\"HighCPU\\"}","truncatedAlerts":0}
            """;

    @Test
    @SuppressWarnings("unchecked")
    void the_real_alertmanager_envelope_is_parsed() throws Exception {
        when(ingest.ingest(anyString(), any())).thenReturn(oneStoredEvent());

        mvc.perform(post(URL).queryParam("clusterId", "c1")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(REAL_ENVELOPE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(1));

        org.mockito.ArgumentCaptor<List<com.coding.platformapi.models.AlertWebhookRequest.Alert>> cap =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(ingest).ingest(eq("c1"), cap.capture());
        assertThat(cap.getValue()).hasSize(1);
        // 状态是**字符串**，不是对象
        assertThat(cap.getValue().get(0).getStatus()).isEqualTo("firing");
        assertThat(cap.getValue().get(0).getLabels()).containsEntry("namespace", "ns1");
    }

    /** 信封里 {@code alerts} 为空也要 200 + accepted:0（批次可能为空）。 */
    @Test
    void an_empty_envelope_is_accepted_with_zero() throws Exception {
        when(ingest.ingest(anyString(), any())).thenReturn(java.util.List.of());

        mvc.perform(post(URL).queryParam("clusterId", "c1")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"4\",\"status\":\"firing\",\"alerts\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(0));
    }

    /**
     * **Review Focus #5**：投递引擎抛任何异常都不能影响 webhook 的 200。
     * 真实环境里 {@code @Async} 会把异常吞在 executor 里；这条守的是「即使同步抛出也不影响响应」。
     */
    @Test
    void a_dispatch_failure_never_changes_the_webhook_status() throws Exception {
        when(ingest.ingest(anyString(), any())).thenReturn(oneStoredEvent());
        doThrow(new RuntimeException("dispatch exploded")).when(dispatch).dispatch(anyString(), any());

        mvc.perform(post(URL).queryParam("clusterId", "c1")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(REAL_ENVELOPE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(1));
    }

    /** 落库了才投递：一批都没落成时不该触发投递。 */
    @Test
    void dispatch_is_not_triggered_when_nothing_was_stored() throws Exception {
        when(ingest.ingest(anyString(), any())).thenReturn(List.of());

        mvc.perform(post(URL).queryParam("clusterId", "c1")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(REAL_ENVELOPE))
                .andExpect(status().isOk());

        verify(dispatch, never()).dispatch(anyString(), any());
    }
}
