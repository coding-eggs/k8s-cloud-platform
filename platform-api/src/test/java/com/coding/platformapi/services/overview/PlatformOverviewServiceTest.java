package com.coding.platformapi.services.overview;

import com.coding.common.models.k8s.dto.ClusterSnapshotDTO;
import com.coding.common.models.k8s.dto.NamespaceSnapshotDTO;
import com.coding.common.models.k8s.dto.NamespaceStatDTO;
import com.coding.common.models.k8s.dto.NodeHealthDTO;
import com.coding.common.models.k8s.dto.ResourceCapacityDTO;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.auth.PlatformUserTenantMapper;
import com.coding.data.mapper.k8s.PlatformAlertEventMapper;
import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.platformapi.models.overview.PlatformOverviewView;
import com.coding.platformapi.services.ClusterService;
import com.coding.platformapi.services.MiddlewareService;
import com.coding.platformapi.services.NamespaceStatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformOverviewServiceTest {

    private final ClusterService clusters = mock(ClusterService.class);
    private final NamespaceStatService stats = mock(NamespaceStatService.class);
    private final MiddlewareService middleware = mock(MiddlewareService.class);
    private final PlatformApplicationMapper apps = mock(PlatformApplicationMapper.class);
    private final PlatformAlertEventMapper alerts = mock(PlatformAlertEventMapper.class);
    private final PlatformTenantMapper tenants = mock(PlatformTenantMapper.class);
    private final PlatformUserTenantMapper userTenants = mock(PlatformUserTenantMapper.class);
    private final PlatformTenantNamespaceMapper allocations = mock(PlatformTenantNamespaceMapper.class);
    private final PlatformHealthProbe health = mock(PlatformHealthProbe.class);

    private PlatformOverviewService service;

    @BeforeEach
    void setUp() {
        service = new PlatformOverviewService(clusters, stats, middleware, apps, alerts,
                tenants, userTenants, allocations, health);
        when(health.probe()).thenReturn(new PlatformHealthProbe.ServiceHealth(1, 1, List.of()));
        when(clusters.list()).thenReturn(List.of(cluster("A"), cluster("B")));
    }

    private static K8sCluster cluster(String id) {
        K8sCluster c = new K8sCluster();
        c.setClusterId(id);
        c.setClusterName(id);
        c.setEnabled(1);
        return c;
    }

    private static NodeHealthDTO node(String name, boolean ready) {
        NodeHealthDTO n = new NodeHealthDTO();
        n.setName(name);
        n.setReady(ready);
        return n;
    }

    private static ClusterSnapshotDTO snap(List<String> namespaces) {
        ClusterSnapshotDTO s = new ClusterSnapshotDTO();
        s.setNodeHealth(List.of(node("n1", true), node("n2", true)));
        ResourceCapacityDTO cap = new ResourceCapacityDTO();
        cap.setCpuAllocatable(new BigDecimal("8"));
        cap.setMemoryAllocatable(new BigDecimal("1000"));
        s.setNodeCapacity(cap);
        s.setNamespaceNames(namespaces);
        return s;
    }

    private static NamespaceSnapshotDTO slice(String ns, int running, BigDecimal cpuHard) {
        NamespaceStatDTO st = new NamespaceStatDTO();
        st.setNamespace(ns);
        st.setPodCount(running);
        st.setPodRunning(running);
        st.setCpuRequest(new BigDecimal("1"));
        st.setMemRequest(new BigDecimal("100"));
        st.setQuotaCpuHard(cpuHard);
        NamespaceSnapshotDTO s = new NamespaceSnapshotDTO();
        s.setStat(st);
        return s;
    }

    private static PlatformTenantNamespace alloc(String cluster, String ns) {
        PlatformTenantNamespace a = new PlatformTenantNamespace();
        a.setClusterId(cluster);
        a.setNamespace(ns);
        a.setTenantId("t1");
        return a;
    }

    @Test
    void unavailableClusterExcludedFromTotals() {
        when(clusters.snapshot("A")).thenReturn(snap(List.of("demo")));
        when(clusters.snapshot("B")).thenReturn(null);
        when(stats.rows(eq(null), eq("A"), any())).thenReturn(List.of(slice("demo", 5, null)));

        PlatformOverviewView v = service.overview();

        assertThat(v.clusters().connected()).isEqualTo(1);
        assertThat(v.clusters().unavailable()).isEqualTo(1);
        assertThat(v.clusters().unavailableNames()).containsExactly("B");
        assertThat(v.pods().running()).isEqualTo(5);
        assertThat(v.nodes().ready()).isEqualTo(2);
        assertThat(v.capacity().cpuAllocatable()).isEqualByComparingTo("8");
        assertThat(v.capacity().cpuRequest()).isEqualByComparingTo("1");
    }

    @Test
    void quotaNullWhenNoNamespaceHasHard() {
        when(clusters.snapshot(any())).thenReturn(snap(List.of("demo")));
        when(stats.rows(eq(null), any(), any())).thenReturn(List.of(slice("demo", 1, null)));

        assertThat(service.overview().quota()).isNull();
    }

    @Test
    void namespacesClassifiedPerCluster() {
        when(clusters.snapshot("A")).thenReturn(snap(List.of("kube-system", "demo", "x")));
        when(clusters.snapshot("B")).thenReturn(null);
        when(stats.rows(eq(null), eq("A"), any())).thenReturn(List.of(
                slice("kube-system", 1, null), slice("demo", 1, null), slice("x", 1, null)));
        // 别的集群同名 ns 的分配行不能把 A 的 x 算成已分配
        when(allocations.listAll()).thenReturn(List.of(alloc("A", "demo"), alloc("B", "x")));

        PlatformOverviewView.Namespaces ns = service.overview().namespaces();

        assertThat(ns.system()).isEqualTo(1);
        assertThat(ns.allocated()).isEqualTo(1);
        assertThat(ns.unallocated()).isEqualTo(1);
    }

    @Test
    void allClustersUnavailableGivesNullK8sBlocks() {
        when(clusters.snapshot(any())).thenReturn(null);

        PlatformOverviewView v = service.overview();

        assertThat(v.capacity()).isNull();
        assertThat(v.clusters().unavailable()).isEqualTo(2);
        // 取不到 ≠ 0：前端显示「—」
        assertThat(v.pods()).isNull();
        assertThat(v.workloads()).isNull();
        assertThat(v.nodes()).isNull();
        assertThat(v.namespaces()).isNull();
        assertThat(v.middleware()).isNull();
    }

    @Test
    void appsAndAlertsCountOnlyConnectedClusters() {
        when(clusters.snapshot("A")).thenReturn(snap(List.of("demo")));
        when(clusters.snapshot("B")).thenReturn(null);
        when(stats.rows(eq(null), eq("A"), any())).thenReturn(List.of(slice("demo", 1, null)));
        com.coding.data.models.system.StatusCount ready = new com.coding.data.models.system.StatusCount();
        ready.setStatus("READY");
        ready.setCount(2);
        when(apps.countByStatus(eq("A"), any())).thenReturn(List.of(ready));
        when(apps.countByStatus(eq("B"), any())).thenReturn(List.of(ready));
        when(apps.countByStatus(eq(null), any())).thenReturn(List.of(ready, ready));

        assertThat(service.overview().applications().total()).isEqualTo(2);
        org.mockito.Mockito.verify(alerts, org.mockito.Mockito.never()).countFiringBySeverity(eq(null), any());
        org.mockito.Mockito.verify(alerts, org.mockito.Mockito.never()).countFiringBySeverity(eq("B"), any());
    }

    @Test
    void byNamespaceSplitsPodBucketsPerClusterAndSkipsEmptyNamespaces() {
        when(clusters.snapshot("A")).thenReturn(snap(List.of("demo", "idle")));
        when(clusters.snapshot("B")).thenReturn(snap(List.of("demo")));
        NamespaceSnapshotDTO busy = slice("demo", 3, null);
        busy.getStat().setPodCount(7);      // 3 running + 1 pending + 1 succeeded + 2 异常
        busy.getStat().setPodPending(1);
        busy.getStat().setPodSucceeded(1);
        busy.setUnhealthyWorkloadCount(2);
        NamespaceSnapshotDTO idle = slice("idle", 0, null);   // 没 Pod、没不健康 → 不出现
        when(stats.rows(eq(null), eq("A"), any())).thenReturn(List.of(busy, idle));
        when(stats.rows(eq(null), eq("B"), any())).thenReturn(List.of(slice("demo", 1, null)));

        List<PlatformOverviewView.NamespaceCounts> rows = service.overview().byNamespace();

        assertThat(rows).extracting(PlatformOverviewView.NamespaceCounts::clusterId,
                        PlatformOverviewView.NamespaceCounts::namespace)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("A", "demo"),
                        org.assertj.core.groups.Tuple.tuple("B", "demo"));
        PlatformOverviewView.NamespaceCounts a = rows.get(0);
        assertThat(a.running()).isEqualTo(3);
        assertThat(a.pending()).isEqualTo(1);
        assertThat(a.succeeded()).isEqualTo(1);
        assertThat(a.abnormal()).isEqualTo(2);
        assertThat(a.unhealthyWorkloads()).isEqualTo(2);
        assertThat(a.clusterName()).isEqualTo("A");
    }

    @Test
    void byNamespaceCarriesMiddlewareBucketsIncludingNamespacesWithoutPods() {
        when(clusters.snapshot("A")).thenReturn(snap(List.of("demo", "cache")));
        when(stats.rows(eq(null), eq("A"), any())).thenReturn(List.of(slice("demo", 2, null)));
        when(middleware.liveStatuses("A")).thenReturn(List.of(
                new MiddlewareService.LiveStatus("demo", "t1", "FAILED"),
                new MiddlewareService.LiveStatus("demo", "t1", "READY"),
                new MiddlewareService.LiveStatus("demo", "t1", "DEGRADED"),
                new MiddlewareService.LiveStatus("cache", "t1", "CREATING")));

        List<PlatformOverviewView.NamespaceCounts> rows = service.overview().byNamespace();

        assertThat(rows).extracting(PlatformOverviewView.NamespaceCounts::namespace)
                .containsExactly("demo", "cache");
        PlatformOverviewView.NamespaceCounts demo = rows.get(0);
        assertThat(demo.running()).isEqualTo(2);
        assertThat(demo.middlewareNormal()).isEqualTo(1);
        assertThat(demo.middlewareAbnormal()).isEqualTo(2);
        assertThat(demo.middlewareProcessing()).isZero();
        PlatformOverviewView.NamespaceCounts cache = rows.get(1);   // 没有 Pod 快照也要列出（中间件在那）
        assertThat(cache.running()).isZero();
        assertThat(cache.middlewareProcessing()).isEqualTo(1);
    }

    @Test
    void byNamespaceNullWhenNoClusterConnected() {
        when(clusters.snapshot(any())).thenReturn(null);

        assertThat(service.overview().byNamespace()).isNull();
    }

    @Test
    void offlineClusterIsNotSampled() {
        K8sCluster down = cluster("B");
        down.setStatus(K8sCluster.ClusterStatus.ERROR);
        when(clusters.list()).thenReturn(List.of(cluster("A"), down));
        when(clusters.snapshot("A")).thenReturn(snap(List.of("demo")));
        when(stats.rows(eq(null), eq("A"), any())).thenReturn(List.of(slice("demo", 1, null)));

        PlatformOverviewView v = service.overview();

        assertThat(v.clusters().unavailableNames()).containsExactly("B");
        org.mockito.Mockito.verify(clusters, org.mockito.Mockito.never()).snapshot("B");
    }
}
