package com.coding.platformapi.services.overview;

import com.coding.common.models.k8s.dto.ClusterSnapshotDTO;
import com.coding.data.mapper.k8s.PlatformAlertEventMapper;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.auth.PlatformUserTenantMapper;
import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.mapper.system.PlatformPipelineMapper;
import com.coding.data.models.system.StatusCount;
import com.coding.platformapi.configs.NamespaceProtectionProperties;
import com.coding.platformapi.models.overview.PlatformOverviewView.Namespaces;
import com.coding.platformapi.models.overview.ScopeSummaryView;
import com.coding.platformapi.services.ClusterService;
import com.coding.platformapi.services.MiddlewareService;
import com.coding.platformapi.services.NamespaceStatService;
import com.coding.platformapi.services.TenantOverviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScopeSummaryServiceTest {

    private final ClusterService clusters = mock(ClusterService.class);
    private final NamespaceStatService stats = mock(NamespaceStatService.class);
    private final MiddlewareService middleware = mock(MiddlewareService.class);
    private final PlatformApplicationMapper apps = mock(PlatformApplicationMapper.class);
    private final PlatformPipelineMapper pipelines = mock(PlatformPipelineMapper.class);
    private final PlatformAlertEventMapper alerts = mock(PlatformAlertEventMapper.class);
    private final PlatformUserTenantMapper userTenants = mock(PlatformUserTenantMapper.class);
    private final PlatformTenantNamespaceMapper allocations = mock(PlatformTenantNamespaceMapper.class);
    private final TenantOverviewService tenantOverview = mock(TenantOverviewService.class);
    private final OverviewPermissions permissions = mock(OverviewPermissions.class);
    private final NamespaceProtectionProperties protection = new NamespaceProtectionProperties();

    private ScopeSummaryService service;

    @BeforeEach
    void setUp() {
        service = new ScopeSummaryService(clusters, stats, middleware, apps, pipelines, alerts,
                userTenants, allocations, tenantOverview, permissions, protection);
        when(permissions.has(anyString())).thenReturn(true);
    }

    private static StatusCount count(String status, long n) {
        StatusCount c = new StatusCount();
        c.setStatus(status);
        c.setCount(n);
        return c;
    }

    @Test
    void memberWithoutAlertPermissionGetsNullAlerts() {
        when(permissions.has("tenant:alert:list")).thenReturn(false);
        when(permissions.has("platform:alert:read")).thenReturn(false);
        when(tenantOverview.allocationsByCluster("t1")).thenReturn(Map.of("c1", List.of("a")));

        ScopeSummaryView v = service.tenant("t1");

        assertThat(v.alerts()).isNull();
        assertThat(v.applications()).isNotNull();
    }

    @Test
    void tenantScopeSumsAcrossClusters() {
        when(tenantOverview.allocationsByCluster("t1")).thenReturn(Map.of("c1", List.of("a"), "c2", List.of("b")));
        when(apps.countByStatus(eq("c1"), any())).thenReturn(List.of(count("READY", 2)));
        when(apps.countByStatus(eq("c2"), any())).thenReturn(List.of(count("READY", 1)));
        when(alerts.countFiringByNamespace("c1", List.of("a"))).thenReturn(List.of(
                new PlatformAlertEventMapper.NamespaceSeverityCount("a", "warning", 2)));
        when(alerts.countFiringByNamespace("c2", List.of("b"))).thenReturn(List.of(
                new PlatformAlertEventMapper.NamespaceSeverityCount("b", "critical", 1)));

        ScopeSummaryView v = service.tenant("t1");

        assertThat(v.applications().total()).isEqualTo(3);
        assertThat(v.namespaceCount()).isEqualTo(2L);
        assertThat(v.alerts().total()).isEqualTo(3);
        assertThat(v.alerts().byNamespace()).extracting(row -> row.clusterId() + "/" + row.namespace())
                .containsExactlyInAnyOrder("c1/a", "c2/b");
    }

    @Test
    void namespaceScopeFiltersMiddlewareByNamespace() {
        when(middleware.liveStatuses("c1")).thenReturn(List.of(
                new MiddlewareService.LiveStatus("a", "t1", "READY"),
                new MiddlewareService.LiveStatus("b", "t1", "READY")));

        assertThat(service.namespace("c1", "a").middleware().total()).isEqualTo(1);
    }

    @Test
    void clusterScopeUsesConfiguredProtectedNamespaces() {
        protection.setProtectedNamespaces(List.of("ops-shared"));
        ClusterSnapshotDTO snapshot = new ClusterSnapshotDTO();
        snapshot.setNamespaceNames(List.of("ops-shared", "kube-custom", "monitor"));
        when(clusters.snapshot("c1")).thenReturn(snapshot);

        assertThat(service.cluster("c1").namespaces()).isEqualTo(new Namespaces(0, 2, 1));
    }

    @Test
    void clusterScopeMiddlewareUnavailableWhenSnapshotMissing() {
        when(clusters.snapshot("c1")).thenReturn(null);
        when(middleware.liveStatuses("c1")).thenReturn(List.of(new MiddlewareService.LiveStatus("a", "t1", "READY")));

        assertThat(service.cluster("c1").middleware()).isNull();
    }

    @Test
    void tenantScopeIgnoresOtherTenantsMiddlewareInSameNamespace() {
        when(tenantOverview.allocationsByCluster("t1")).thenReturn(Map.of("c1", List.of("a")));
        when(middleware.liveStatuses("c1")).thenReturn(List.of(
                new MiddlewareService.LiveStatus("a", "t1", "READY"),
                new MiddlewareService.LiveStatus("a", "t2", "READY")));

        assertThat(service.tenant("t1").middleware().total()).isEqualTo(1);
    }
}
