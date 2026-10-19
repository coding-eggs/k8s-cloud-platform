package com.coding.platformapi.logs;

import com.coding.common.exception.CloudPlatformException;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.platformapi.services.ClusterService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LogQueryServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final EsLogClient es = mock(EsLogClient.class);
    private final PlatformTenantNamespaceMapper allocations = mock(PlatformTenantNamespaceMapper.class);
    private final ClusterService clusters = mock(ClusterService.class);
    private final LogQueryService svc = new LogQueryService(es, allocations, clusters);

    @Test
    void indicesAreDailyAndPaddedOneDayOnEachSide() {
        // 2026-10-19T05:00Z ~ 2026-10-20T05:00Z（UTC）→ 前两天到后一天
        long start = 1792386000000L; // 2026-10-19T05:00:00Z
        long end = start + 24 * 3600_000L;

        assertThat(svc.indices(start, end)).containsExactly(
                "otel-logs-2026.10.18", "otel-logs-2026.10.19",
                "otel-logs-2026.10.20", "otel-logs-2026.10.21");
    }

    @Test
    void theQueryCarriesEveryFilterAndParsesFlatDocuments() {
        when(es.search(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(JSON.readTree("""
                {"hits":{"hits":[{"_source":{
                  "@timestamp":"2026-10-19T05:00:00Z",
                  "SeverityText":"ERROR","SeverityNumber":17,"Body":"boom",
                  "TraceId":"5d3420f19d14ade9f891c932f1ca152c","SpanId":"4a645bfe740d74db",
                  "Resource.service.name":"movie-deployment",
                  "Resource.cluster.name":"coding-cluster",
                  "Resource.k8s.namespace.name":"default",
                  "Resource.k8s.pod.name":"movie-abc",
                  "Resource.k8s.deployment.name":"movie",
                  "Attributes.http.method":"GET"
                },"sort":[1000,2]}]}}
                """));

        LogQueryRequest req = new LogQueryRequest();
        req.setClusterName("coding-cluster");
        req.setNamespace("default");
        req.setWorkload("movie");
        req.setPod("movie-abc");
        req.setKeyword("boom");
        req.setSeverity("error");
        req.setStartMillis(1792386000000L);
        req.setEndMillis(1792389600000L);

        var rows = svc.search(req, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(es).search(eq("otel-logs-2026.10.18,otel-logs-2026.10.19,otel-logs-2026.10.20"),
                body.capture());
        String json = JSON.writeValueAsString(body.getValue());
        assertThat(json).contains("Resource.cluster.name.keyword").contains("coding-cluster")
                .contains("Resource.k8s.namespace.name.keyword")
                .contains("SeverityText.keyword").contains("ERROR")
                .contains("Resource.k8s.deployment.name.keyword")
                // Pod 是独立条件（表格不再展示，但查询要支持）
                .contains("Resource.k8s.pod.name.keyword").contains("movie-abc")
                .contains("simple_query_string").contains("boom");

        assertThat(rows).hasSize(1);
        LogEntryDTO row = rows.get(0);
        assertThat(row.getTime()).isEqualTo("2026-10-19T05:00:00Z");
        assertThat(row.getSeverity()).isEqualTo("ERROR");
        assertThat(row.getBody()).isEqualTo("boom");
        assertThat(row.getService()).isEqualTo("movie-deployment");
        assertThat(row.getNamespace()).isEqualTo("default");
        assertThat(row.getPod()).isEqualTo("movie-abc");
        assertThat(row.getWorkload()).isEqualTo("movie");
        assertThat(row.getAttributes()).containsEntry("http.method", "GET");
        assertThat(row.getSort()).containsExactly(1000L, 2L);
    }

    @Test
    void traceSampledFlagFollowsTheLowBitOfTraceFlags() {
        // 采样率 < 100% 时，日志里的 traceId 大多没有被导出到 Tempo（点过去 404）。
        // TraceFlags 的 bit0 = W3C sampled；bit1 是 random-trace-id，不能当采样标记用。
        when(es.search(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(JSON.readTree("""
                {"hits":{"hits":[
                  {"_source":{"TraceId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","TraceFlags":3}},
                  {"_source":{"TraceId":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","TraceFlags":2}},
                  {"_source":{"TraceId":"cccccccccccccccccccccccccccccccc"}}
                ]}}
                """));

        LogQueryRequest req = new LogQueryRequest();
        req.setStartMillis(1792386000000L);
        req.setEndMillis(1792389600000L);

        var rows = svc.search(req, null);

        assertThat(rows).extracting(LogEntryDTO::getSampled)
                .containsExactly(true, false, null);
    }

    @Test
    void aForcedTenantNarrowsTheQueryToItsAllocatedNamespaces() {
        PlatformTenantNamespace row = new PlatformTenantNamespace();
        row.setTenantId("t1");
        row.setClusterId("c1");
        row.setNamespace("movie-ns");
        when(allocations.listByTenant("t1")).thenReturn(List.of(row));
        when(clusters.requireName("c1")).thenReturn("coding-cluster");
        when(es.search(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(emptyHits());

        LogQueryRequest req = new LogQueryRequest();
        req.setTenantId("someone-else"); // 会被 forced 覆盖
        req.setStartMillis(1792386000000L);
        req.setEndMillis(1792389600000L);

        svc.search(req, "t1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(es).search(anyString(), body.capture());
        String json = JSON.writeValueAsString(body.getValue());
        assertThat(json).contains("coding-cluster").contains("movie-ns")
                .doesNotContain("someone-else");
    }

    @Test
    void aTenantWithoutAllocationsIsRejected() {
        when(allocations.listByTenant("t9")).thenReturn(List.of());

        LogQueryRequest req = new LogQueryRequest();
        req.setStartMillis(1792386000000L);
        req.setEndMillis(1792389600000L);

        assertThatThrownBy(() -> svc.search(req, "t9"))
                .hasMessageContaining("没有分配任何命名空间");
    }

    @Test
    void anInvertedOrTooWideRangeIsRejected() {
        LogQueryRequest inverted = new LogQueryRequest();
        inverted.setStartMillis(2000L);
        inverted.setEndMillis(1000L);
        assertThatThrownBy(() -> svc.search(inverted, null)).hasMessageContaining("时间范围");

        LogQueryRequest wide = new LogQueryRequest();
        wide.setStartMillis(1792386000000L);
        wide.setEndMillis(1792386000000L + 40L * 24 * 3600_000L);
        assertThatThrownBy(() -> svc.search(wide, null)).hasMessageContaining("31 天");
    }

    private static JsonNode emptyHits() {
        return JSON.readTree("{\"hits\":{\"hits\":[]}}");
    }
}
