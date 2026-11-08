package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.app.AppNodeDTO;
import com.coding.common.models.app.AppRenderRequestDTO;
import com.coding.data.mapper.system.PlatformAppReleaseMapper;
import com.coding.data.mapper.system.PlatformAppServiceMapper;
import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.models.system.PlatformAppExecution;
import com.coding.data.models.system.PlatformAppRelease;
import com.coding.data.models.system.PlatformAppService;
import com.coding.data.models.system.PlatformApplication;
import com.coding.data.models.system.PlatformIngressPort;
import com.coding.platformapi.app.AppSnapshot;
import com.coding.platformapi.services.IngressPortService.PortRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppReleasePipelineTest {

    private static final PortRange POOL = new PortRange(20000, 20999);

    private final PlatformApplicationMapper apps = mock(PlatformApplicationMapper.class);
    private final PlatformAppReleaseMapper releases = mock(PlatformAppReleaseMapper.class);
    private final AppDependencyService dependencies = mock(AppDependencyService.class);
    private final PlatformAppServiceMapper services = mock(PlatformAppServiceMapper.class);
    private final IngressPortService ports = mock(IngressPortService.class);
    private final IngressListenerSync sync = mock(IngressListenerSync.class);
    private final AppReleasePipeline pipeline =
            new AppReleasePipeline(apps, releases, dependencies, services, ports, sync);
    private PlatformApplication app;

    @BeforeEach
    void setUp() {
        app = new PlatformApplication();
        app.setId("app1");
        app.setTenantId("t1");
        app.setClusterId("c1");
        app.setNamespace("demo");
        app.setSlug("shop");
        when(apps.selectById("app1")).thenReturn(app);
        when(dependencies.notReady(any())).thenReturn(List.of());
        when(dependencies.connectionsOf(any(), anyString())).thenReturn(Map.of());
        when(ports.currentPool("c1")).thenReturn(POOL);
    }

    private static Map<String, Object> svc(String expose) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("imageRepo", "nginx");
        d.put("imageTag", "1");
        d.put("port", 8080);
        if (expose != null) {
            d.put("expose", expose);
        }
        return d;
    }

    private PlatformAppExecution execution(Map<String, Map<String, Object>> services) {
        PlatformAppRelease release = new PlatformAppRelease();
        release.setAppId("app1");
        release.setReleaseNo(3);
        release.setSnapshotJson(new AppSnapshot(services, List.of()).toJson());
        when(releases.selectByNo("app1", 3)).thenReturn(release);
        PlatformAppExecution e = new PlatformAppExecution();
        e.setAppId("app1");
        e.setReleaseNo(3);
        return e;
    }

    private static PlatformAppService row(String key, Map<String, Object> desired) {
        PlatformAppService r = new PlatformAppService();
        r.setAppId("app1");
        r.setServiceKey(key);
        r.setDesiredJson(JsonMapper.builder().build().writeValueAsString(desired));
        r.setPendingDelete(false);
        return r;
    }

    @Test
    @SuppressWarnings("unchecked")
    void renderAddsRouteAndClusterIp() {
        when(ports.portOf("app1", "api")).thenReturn(20003);

        AppRenderRequestDTO req = pipeline.buildRequest(app, 3,
                new AppSnapshot(Map.of("api", svc("gateway-tcp")), List.of()));

        AppNodeDTO node = req.getSpec().getNodes().get(0);
        assertThat(node.getParams()).containsEntry("expose", "none");
        assertThat((Map<String, Object>) node.getParams().get("route")).isEqualTo(Map.of(
                "kind", "tcp", "parentName", "platform-ingress", "sectionName", "p20003"));
    }

    @Test
    void renderFailsWithoutAllocatedPort() {
        when(ports.portOf("app1", "api")).thenReturn(null);

        assertThatThrownBy(() -> pipeline.buildRequest(app, 3,
                new AppSnapshot(Map.of("api", svc("gateway-http")), List.of()))).hasMessageContaining("端口");
    }

    @Test
    @SuppressWarnings("unchecked")
    void previewRendersWithoutAllocatedPort() {
        // 下线后重新上线：端口在 PRECHECK 才重新分配，预览不能因此报「渲染失败」挡住发布
        when(ports.portOf("app1", "api")).thenReturn(null);

        AppRenderRequestDTO req = pipeline.buildRequest(app, 3,
                new AppSnapshot(Map.of("api", svc("gateway-http")), List.of()), true);

        Map<String, Object> route = (Map<String, Object>) req.getSpec().getNodes().get(0).getParams().get("route");
        assertThat(route).containsEntry("kind", "http");
    }

    @Test
    void precheckReallocatesAfterOfflineAndSyncs() {
        PlatformAppExecution e = execution(Map.of("api", svc("gateway-tcp")));
        when(ports.portOf("app1", "api")).thenReturn(null);
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("gateway-tcp"))));

        pipeline.check(e);

        verify(ports).allocate("c1", "demo", "app1", "api", "TCP", POOL);
        verify(sync).sync("c1", "demo");
    }

    @Test
    void precheckFailsWhenListenerSetUnsupported() {
        PlatformAppExecution e = execution(Map.of("api", svc("gateway-http")));
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("gateway-http"))));
        doThrow(new CloudPlatformException(EnumResponseType.ERROR, "集群缺少 ListenerSet CRD 或入口 Gateway"))
                .when(sync).sync("c1", "demo");

        assertThatThrownBy(() -> pipeline.check(e)).hasMessageContaining("ListenerSet");
    }

    @Test
    void precheckFailsWithoutPool() {
        PlatformAppExecution e = execution(Map.of("api", svc("gateway-http")));
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("gateway-http"))));
        when(ports.currentPool("c1")).thenReturn(null);

        assertThatThrownBy(() -> pipeline.check(e)).hasMessageContaining("端口池");
        verify(sync, never()).sync(anyString(), anyString());
    }

    @Test
    void precheckLeavesPortsOfServicesLeavingTheGatewayInPlace() {
        // 改离网关方式：发布前不摘 listener —— 发布失败时线上旧版本的路由还要用它（M6）
        PlatformAppExecution e = execution(Map.of("api", svc(null)));
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc(null))));
        when(ports.activeOfApp("app1")).thenReturn(List.of(held("api")));

        pipeline.check(e);

        verify(ports, never()).release(anyString(), anyString());
        verify(sync, never()).syncReleasing(anyString(), anyString(), any(), any());
    }

    @Test
    void afterReleaseReleasesPortsNoLongerExposedThroughTheGateway() {
        PlatformAppExecution e = execution(Map.of("api", svc(null)));
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc(null))));
        when(ports.activeOfApp("app1")).thenReturn(List.of(held("api")));
        runReleaseCallback();

        pipeline.afterRelease(e);

        verify(ports).release("app1", "api");
        verify(sync).syncReleasing(eq("c1"), eq("demo"), any(), any());
    }

    @Test
    void afterReleaseKeepsPortOfAServiceSavedAsGatewayButNotSelected() {
        PlatformAppExecution e = execution(Map.of("api", svc(null)));
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("gateway-http"))));
        when(ports.activeOfApp("app1")).thenReturn(List.of(held("api")));

        pipeline.afterRelease(e);

        verify(ports, never()).release(anyString(), anyString());
        verify(sync, never()).syncReleasing(anyString(), anyString(), any(), any());
    }

    @Test
    void afterReleaseKeepsPortsWhenSyncFails() {
        // 发布已成功，不因此失败；端口保留，下次成功发布再放
        PlatformAppExecution e = execution(Map.of("api", svc(null)));
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc(null))));
        when(ports.activeOfApp("app1")).thenReturn(List.of(held("api")));
        doThrow(new RuntimeException("集群不可达"))
                .when(sync).syncReleasing(eq("c1"), eq("demo"), any(), any());

        pipeline.afterRelease(e);

        verify(ports, never()).release(anyString(), anyString());
    }

    private static PlatformIngressPort held(String key) {
        PlatformIngressPort p = new PlatformIngressPort();
        p.setAppId("app1");
        p.setServiceKey(key);
        p.setPort(20001);
        return p;
    }

    /** sync 是 mock：让它像真的一样在下发成功后执行释放回调 */
    private void runReleaseCallback() {
        doAnswer(inv -> {
            inv.<Runnable>getArgument(3).run();
            return null;
        }).when(sync).syncReleasing(anyString(), anyString(), any(), any());
    }

    @Test
    void precheckKeepsPortOfAServiceSavedAsGatewayButNotSelected() {
        // 期望配置是网关方式、只是这次没勾选它：快照里仍是旧配置，端口不能被释放
        PlatformAppExecution e = execution(Map.of("api", svc(null)));
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("gateway-http"))));
        PlatformIngressPort held = new PlatformIngressPort();
        held.setAppId("app1");
        held.setServiceKey("api");
        held.setPort(20001);
        when(ports.activeOfApp("app1")).thenReturn(List.of(held));

        pipeline.check(e);

        verify(ports, never()).release(anyString(), anyString());
    }

    @Test
    void precheckSkipsSyncForAppsWithoutGateway() {
        PlatformAppExecution e = execution(Map.of("api", svc("nodeport")));
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("nodeport"))));

        pipeline.check(e);

        verify(sync, never()).sync(anyString(), anyString());
    }

    @Test
    void afterOfflineReleasesPortsAndSyncs() {
        PlatformAppExecution e = execution(Map.of());
        runReleaseCallback();

        pipeline.afterOffline(e);

        verify(ports).releaseApp("app1");
        verify(sync).syncReleasing(eq("c1"), eq("demo"), any(), any());
    }

    @Test
    void afterOfflineKeepsPortsWhenSyncFails() {
        // 下线已成功，不因此失败；端口保留（listener 还在），再上线复用、删应用时再放
        PlatformAppExecution e = execution(Map.of());
        doThrow(new RuntimeException("集群不可达"))
                .when(sync).syncReleasing(eq("c1"), eq("demo"), any(), any());

        pipeline.afterOffline(e);

        verify(ports, never()).releaseApp("app1");
    }
}
