package com.coding.platformapi.controllers;

import com.coding.platformapi.configs.AlertWebhookTokenCheck;
import com.coding.platformapi.services.AlertIngestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ingest = mock(AlertIngestService.class);
        AlertWebhookTokenCheck check = new AlertWebhookTokenCheck(TOKEN);
        mvc = MockMvcBuilders.standaloneSetup(new AlertWebhookController(ingest, check)).build();
    }

    private static final String ONE_ALERT = """
            [{"status":{"state":"firing"},
              "labels":{"alertname":"HighCPU","namespace":"ns1","severity":"warning"},
              "annotations":{"summary":"CPU 高"},
              "startsAt":"2026-10-17T10:00:00Z",
              "fingerprint":"fp-1"}]
            """;

    /** 密钥对 → 200 + {"accepted": N}。 */
    @Test
    void a_valid_token_ingests_and_returns_200() throws Exception {
        when(ingest.ingest(anyString(), any())).thenReturn(1);

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
        when(ingest.ingest(anyString(), any())).thenReturn(1);

        mvc.perform(post(URL).queryParam("clusterId", "c1")
                        .header("Authorization", TOKEN)          // ← 不带前缀
                        .contentType(MediaType.APPLICATION_JSON).content(ONE_ALERT))
                .andExpect(status().isOk());
    }

    /** 空数组 → 200 + accepted: 0（Alertmanager 有时会推空批次）。 */
    @Test
    void an_empty_array_is_accepted_with_zero() throws Exception {
        when(ingest.ingest(anyString(), any())).thenReturn(0);

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
}
