package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.app.AppPruneRequestDTO;
import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.mapper.system.PlatformApplicationResourceMapper;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.models.system.PlatformApplication;
import com.coding.data.models.system.PlatformApplicationResource;
import com.coding.platformapi.k8s.K8sAppClient;
import com.coding.platformapi.models.ApplicationCreateRequest;
import com.coding.platformapi.models.ApplicationDeleteRequest;
import com.coding.platformapi.models.ApplicationView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApplicationServiceTest {

    private PlatformApplicationMapper apps;
    private PlatformApplicationResourceMapper resources;
    private PlatformTenantNamespaceMapper allocations;
    private NamespaceAccessGuard namespaceGuard;
    private K8sAppClient client;
    private AppDependencyService appDependencies;
    private com.coding.data.mapper.system.PlatformAppExecutionMapper appExecutions;
    private ApplicationService service;
    private ClusterService clusterService;

    @BeforeEach
    void setUp() {
        apps = mock(PlatformApplicationMapper.class);
        resources = mock(PlatformApplicationResourceMapper.class);
        allocations = mock(PlatformTenantNamespaceMapper.class);
        namespaceGuard = mock(NamespaceAccessGuard.class);
        client = mock(K8sAppClient.class);
        appDependencies = mock(AppDependencyService.class);
        appExecutions = mock(com.coding.data.mapper.system.PlatformAppExecutionMapper.class);
        ports = mock(IngressPortService.class);
        listeners = mock(IngressListenerSync.class);
        clusterService = mock(ClusterService.class);
        when(clusterService.options()).thenReturn(List.of(
                new ClusterService.ClusterOption("c1", "coding-cluster", 1, null),
                new ClusterService.ClusterOption("c2", "work-cluster", 1, null)));
        service = new ApplicationService(apps, resources, allocations, namespaceGuard, client, appDependencies,
                appExecutions, ports, listeners, clusterService);
    }

    private static ApplicationCreateRequest req() {
        ApplicationCreateRequest r = new ApplicationCreateRequest();
        r.setClusterId("c1");
        r.setNamespace("demo");
        r.setName("Demo App");
        return r;
    }

    private static PlatformApplication row() {
        PlatformApplication a = new PlatformApplication();
        a.setId("app_1");
        a.setTenantId("t1");
        a.setClusterId("c1");
        a.setNamespace("demo");
        a.setName("Demo App");
        a.setSlug("demo-app");
        a.setStatus("READY");
        return a;
    }

    private static PlatformApplicationResource resource(String kind, String name) {
        PlatformApplicationResource r = new PlatformApplicationResource();
        r.setAppId("app_1");
        r.setRevision(1);
        r.setApiVersion("v1");
        r.setKind(kind);
        r.setNamespace("demo");
        r.setName(name);
        return r;
    }

    @Test
    void createPersistsSlugAndDraftStatus() {
        service.create(req(), "admin", "t1");

        ArgumentCaptor<PlatformApplication> cap = ArgumentCaptor.forClass(PlatformApplication.class);
        verify(apps).insert(cap.capture());
        PlatformApplication saved = cap.getValue();
        assertThat(saved.getSlug()).isEqualTo("demo-app");
        assertThat(saved.getStatus()).isEqualTo("DRAFT");
        assertThat(saved.getNamespace()).isEqualTo("demo");
        assertThat(saved.getTenantId()).isEqualTo("t1");
        verify(namespaceGuard).assertAccessible("c1", "demo");
    }

    @Test
    void createRequiresNamespaceAndName() {
        ApplicationCreateRequest noNs = req();
        noNs.setNamespace(" ");
        assertThatThrownBy(() -> service.create(noNs, "admin", "t1")).hasMessageContaining("命名空间");
        ApplicationCreateRequest noName = req();
        noName.setName("");
        assertThatThrownBy(() -> service.create(noName, "admin", "t1")).hasMessageContaining("应用名");
        verify(apps, never()).insert(any());
    }

    @Test
    void createRefusesNamespacesOutsideTheTenantsAllocation() {
        org.mockito.Mockito.doThrow(new CloudPlatformException(
                com.coding.common.exception.EnumResponseType.ERROR, "无权访问命名空间"))
                .when(namespaceGuard).assertAccessible("c1", "demo");
        assertThatThrownBy(() -> service.create(req(), "admin", "t1")).hasMessageContaining("无权");
        verify(apps, never()).insert(any());
    }

    @Test
    void createRequiresTenantEvenForAdmin() {
        assertThatThrownBy(() -> service.create(req(), "admin", null))
                .hasMessageContaining("请先切换到某个租户");
        verify(apps, never()).insert(any());
    }

    @Test
    void getHidesOtherTenantsApplications() {
        when(apps.selectById("app_1")).thenReturn(row());

        assertThatThrownBy(() -> service.get("app_1", "someone-else"))
                .hasMessageContaining("不存在或已删除");
    }

    @Test
    void deletePrunesOwnedObjectsButKeepsData() {
        when(apps.selectById("app_1")).thenReturn(row());
        when(resources.selectByApp("app_1")).thenReturn(List.of(
                resource("Service", "demo-app-web"),
                resource("PersistentVolumeClaim", "demo-app-web-data")));

        ApplicationDeleteRequest req = new ApplicationDeleteRequest();
        req.setAppId("app_1");
        service.delete(req, "admin", null);

        ArgumentCaptor<AppPruneRequestDTO> cap = ArgumentCaptor.forClass(AppPruneRequestDTO.class);
        verify(client).prune(cap.capture());
        assertThat(cap.getValue().isDeletePvc()).isFalse();
        assertThat(cap.getValue().getObjects()).hasSize(2);
        verify(apps).softDelete(eq("app_1"), any());
    }

    @Test
    void deleteDetachesDependenciesAndRefusesDuringActiveRelease() {
        when(apps.selectById("app_1")).thenReturn(row());
        when(appExecutions.selectActiveByApp("app_1"))
                .thenReturn(new com.coding.data.models.system.PlatformAppExecution());
        ApplicationDeleteRequest req = new ApplicationDeleteRequest();
        req.setAppId("app_1");
        assertThatThrownBy(() -> service.delete(req, "admin", null)).hasMessageContaining("发布");
        verify(appDependencies, never()).detachForAppDelete(any());

        when(appExecutions.selectActiveByApp("app_1")).thenReturn(null);
        service.delete(req, "admin", null);
        verify(appDependencies).detachForAppDelete("app_1");
    }

    @Test
    void deletePrunesEveryObjectInTheResourceLedger() {
        PlatformApplication app = row();
        app.setLiveReleaseNo(1);
        when(apps.selectById("app_1")).thenReturn(app);
        when(resources.selectByApp("app_1")).thenReturn(List.of(resource("Deployment", "demo-app-web")));

        ApplicationDeleteRequest req = new ApplicationDeleteRequest();
        req.setAppId("app_1");
        service.delete(req, "admin", "t1");

        verify(client).prune(any());
        verify(apps).softDelete(eq("app_1"), any());
        verify(appDependencies).detachForAppDelete("app_1");
    }

    private IngressPortService ports;
    private IngressListenerSync listeners;

    @Test
    void deleteReleasesGatewayPortsAndSyncs() {
        when(apps.selectById("app_1")).thenReturn(row());
        when(resources.selectByApp("app_1")).thenReturn(List.of());
        ApplicationDeleteRequest req = new ApplicationDeleteRequest();
        req.setAppId("app_1");

        org.mockito.Mockito.doAnswer(inv -> {
            inv.<Runnable>getArgument(3).run();
            return null;
        }).when(listeners).syncReleasing(any(), any(), any(), any());

        service.delete(req, "admin", null);

        verify(ports).releaseApp("app_1");
        verify(listeners).syncReleasing(org.mockito.ArgumentMatchers.eq(row().getClusterId()),
                org.mockito.ArgumentMatchers.eq(row().getNamespace()), any(), any());
    }

    @Test
    void deleteReleasesGatewayPortsEvenWhenSyncFails() {
        // 应用没了，端口不能永远挂在它名下：同步失败也释放（listener 残留由下次该命名空间同步摘掉）
        when(apps.selectById("app_1")).thenReturn(row());
        when(resources.selectByApp("app_1")).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new RuntimeException("集群不可达"))
                .when(listeners).syncReleasing(any(), any(), any(), any());
        ApplicationDeleteRequest req = new ApplicationDeleteRequest();
        req.setAppId("app_1");

        service.delete(req, "admin", null);

        verify(ports).releaseApp("app_1");
    }

    @Test
    void listWithoutClusterCoversAllClustersOfTheTenantAndCarriesClusterNames() {
        // 2026-11-13：应用页按租户展示、不挂顶栏 —— 不传集群就是本租户全部集群，列表要能标出集群名
        PlatformApplication a = new PlatformApplication();
        a.setId("a1"); a.setClusterId("c1"); a.setNamespace("movie"); a.setName("movie");
        PlatformApplication b = new PlatformApplication();
        b.setId("a2"); b.setClusterId("gone"); b.setNamespace("demo"); b.setName("demo");
        when(apps.selectByTenant("t1", null)).thenReturn(List.of(a, b));

        var out = service.list(null, "t1");

        assertThat(out).extracting(ApplicationView::getClusterName).containsExactly("coding-cluster", null);
        assertThat(out).extracting(ApplicationView::getNamespace).containsExactly("movie", "demo");
    }

    @Test
    void optionsWithoutClusterListEveryAllocatedClusterNamespacePairOfTheTenant() {
        when(allocations.listByTenant("t1")).thenReturn(List.of(
                alloc("t1", "c2", "web"), alloc("t1", "c1", "movie"), alloc("t1", "c1", "api"),
                alloc("t1", "c1", "movie"), alloc("t1", "deleted-cluster", "old")));

        var out = service.options(null, "t1");

        assertThat(out.getTargets()).extracting(t -> t.getClusterName() + "/" + t.getNamespace())
                .containsExactly("coding-cluster/api", "coding-cluster/movie", "work-cluster/web");
        assertThat(out.getTargets().get(0).getClusterId()).isEqualTo("c1");
    }

    private static com.coding.data.models.auth.PlatformTenantNamespace alloc(String tenant, String cluster, String ns) {
        var row = new com.coding.data.models.auth.PlatformTenantNamespace();
        row.setTenantId(tenant); row.setClusterId(cluster); row.setNamespace(ns);
        return row;
    }
}
