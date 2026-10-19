package com.coding.platformapi.trace;

import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.platformapi.services.ClusterService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 租户链路边界：列表/服务候选把分配表翻成 Tempo 侧条件；详情在平台侧按 span 过滤
 * （Tempo 的 trace-by-ID 没有「只返回部分 span」的能力）。
 */
class TraceServiceTest {

    private final TempoTraceClient tempo = mock(TempoTraceClient.class);
    private final PlatformTenantNamespaceMapper allocations = mock(PlatformTenantNamespaceMapper.class);
    private final ClusterService clusters = mock(ClusterService.class);
    private final TraceService svc = new TraceService(tempo, allocations, clusters);

    @Test
    void aTenantSearchCarriesItsAllocationPairsAsScopes() {
        when(allocations.listByTenant("t1")).thenReturn(List.of(
                alloc("t1", "c1", "movie-ns"), alloc("t1", "c1", "shared")));
        when(clusters.requireName("c1")).thenReturn("coding-cluster");
        when(tempo.search(any(), anyList())).thenReturn(List.of());

        svc.search(new TraceSearchRequest(), "t1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TraceScope>> scopes = ArgumentCaptor.forClass(List.class);
        verify(tempo).search(any(), scopes.capture());
        assertThat(scopes.getValue()).containsExactly(
                new TraceScope("coding-cluster", "movie-ns"),
                new TraceScope("coding-cluster", "shared"));
    }

    @Test
    void aPlatformAdminSearchHasNoScope() {
        when(tempo.search(any(), anyList())).thenReturn(List.of());

        svc.search(new TraceSearchRequest(), null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TraceScope>> scopes = ArgumentCaptor.forClass(List.class);
        verify(tempo).search(any(), scopes.capture());
        assertThat(scopes.getValue()).isEmpty();
        verify(clusters, org.mockito.Mockito.never()).requireName(any());
    }

    @Test
    void anAdminCanNarrowToTheTenantPickedInTheTopBar() {
        // 平台管理员（无租户帽）用顶栏选中的租户作为筛选条件 —— 与日志查询同一条规矩
        allocation("t1", "c1", "movie-ns");
        when(clusters.requireName("c1")).thenReturn("coding-cluster");
        when(tempo.search(any(), anyList())).thenReturn(List.of());

        TraceSearchRequest req = new TraceSearchRequest();
        req.setTenantId("t1");
        svc.search(req, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TraceScope>> scopes = ArgumentCaptor.forClass(List.class);
        verify(tempo).search(any(), scopes.capture());
        assertThat(scopes.getValue()).containsExactly(new TraceScope("coding-cluster", "movie-ns"));
    }

    @Test
    void aHattedTenantIgnoresTheTenantIdInTheRequest() {
        allocation("t1", "c1", "movie-ns");
        when(clusters.requireName("c1")).thenReturn("coding-cluster");
        when(tempo.search(any(), anyList())).thenReturn(List.of());

        TraceSearchRequest req = new TraceSearchRequest();
        req.setTenantId("t2"); // 试图查别人 → 被帽覆盖
        svc.search(req, "t1");

        verify(allocations).listByTenant("t1");
        verify(allocations, org.mockito.Mockito.never()).listByTenant("t2");
    }

    @Test
    void aTenantDetailOnlyReturnsSpansInsideItsNamespaces() {
        allocation("t1", "c1", "movie-ns");
        when(clusters.requireName("c1")).thenReturn("coding-cluster");
        when(tempo.get("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")).thenReturn(new TraceDetailDTO(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                List.of(span("s1", "coding-cluster", "movie-ns"), span("s2", "coding-cluster", "other-ns"),
                        span("s3", "coding-cluster", null))));

        var out = svc.get("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "t1");

        assertThat(out.getSpans()).extracting(SpanDTO::getSpanId).containsExactly("s1");
    }

    @Test
    void aDetailWithNoVisibleSpanReadsAsNotFound() {
        allocation("t1", "c1", "movie-ns");
        when(clusters.requireName("c1")).thenReturn("coding-cluster");
        when(tempo.get("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")).thenReturn(new TraceDetailDTO(
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                List.of(span("s1", "coding-cluster", "other-ns"))));

        assertThatThrownBy(() -> svc.get("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "t1"))
                .hasMessageContaining("不存在")
                .hasMessageContaining("无权");
    }

    @Test
    void anAdminDetailIsNotFiltered() {
        when(tempo.get("cccccccccccccccccccccccccccccccc")).thenReturn(new TraceDetailDTO(
                "cccccccccccccccccccccccccccccccc",
                List.of(span("s1", "coding-cluster", "movie-ns"), span("s2", "coding-cluster", "other-ns"))));

        var out = svc.get("cccccccccccccccccccccccccccccccc", null);

        assertThat(out.getSpans()).hasSize(2);
        verify(allocations, org.mockito.Mockito.never()).listByTenant(any());
    }

    @Test
    void aTenantWithoutAllocationsIsRejected() {
        when(allocations.listByTenant("t9")).thenReturn(List.of());

        assertThatThrownBy(() -> svc.search(new TraceSearchRequest(), "t9"))
                .hasMessageContaining("没有分配任何命名空间");
    }

    @Test
    void allocationRowsOfDeletedClustersAreSkipped() {
        when(allocations.listByTenant("t1")).thenReturn(List.of(
                alloc("t1", "gone", "dead-ns"), alloc("t1", "c1", "movie-ns")));
        when(clusters.requireName("gone")).thenThrow(new RuntimeException("cluster deleted"));
        when(clusters.requireName("c1")).thenReturn("coding-cluster");
        when(tempo.search(any(), anyList())).thenReturn(List.of());

        svc.search(new TraceSearchRequest(), "t1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TraceScope>> scopes = ArgumentCaptor.forClass(List.class);
        verify(tempo).search(any(), scopes.capture());
        assertThat(scopes.getValue()).containsExactly(new TraceScope("coding-cluster", "movie-ns"));
    }

    @Test
    void serviceOptionsAreScopedForTenantsToo() {
        allocation("t1", "c1", "movie-ns");
        when(clusters.requireName("c1")).thenReturn("coding-cluster");
        when(tempo.serviceNames(eq("coding-cluster"), org.mockito.ArgumentMatchers.isNull(), eq(1000L), eq(2000L), anyList()))
                .thenReturn(List.of("movie"));

        assertThat(svc.serviceNames("coding-cluster", null, 1000L, 2000L, null, "t1"))
                .containsExactly("movie");
    }

    private void allocation(String tenantId, String clusterId, String namespace) {
        when(allocations.listByTenant(tenantId)).thenReturn(List.of(alloc(tenantId, clusterId, namespace)));
    }

    private static PlatformTenantNamespace alloc(String tenantId, String clusterId, String namespace) {
        PlatformTenantNamespace row = new PlatformTenantNamespace();
        row.setTenantId(tenantId);
        row.setClusterId(clusterId);
        row.setNamespace(namespace);
        return row;
    }

    private static SpanDTO span(String spanId, String cluster, String namespace) {
        return new SpanDTO(spanId, null, "op", "svc", cluster, namespace,
                "1", "2", 1L, "STATUS_CODE_OK", null);
    }
}
