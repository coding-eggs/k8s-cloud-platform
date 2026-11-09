package com.coding.platformapi.services;

import com.coding.common.models.app.AppRenderResultDTO;
import com.coding.data.mapper.system.PlatformAppDependencyMapper;
import com.coding.data.mapper.system.PlatformAppExecutionMapper;
import com.coding.data.mapper.system.PlatformAppReleaseMapper;
import com.coding.data.mapper.system.PlatformAppServiceMapper;
import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.models.system.PlatformAppExecution;
import com.coding.data.models.system.PlatformAppRelease;
import com.coding.data.models.system.PlatformAppService;
import com.coding.data.models.system.PlatformApplication;
import com.coding.platformapi.app.AppCanary;
import com.coding.platformapi.app.AppPending;
import com.coding.platformapi.app.AppSnapshot;
import com.coding.platformapi.k8s.K8sAppClient;
import com.coding.platformapi.models.AppCanaryRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppCanaryServiceTest {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private PlatformApplicationMapper apps;
    private PlatformAppServiceMapper services;
    private PlatformAppReleaseMapper releases;
    private PlatformAppExecutionMapper executions;
    private AppCanaryService canary;
    private PlatformApplication app;
    private com.coding.platformapi.metrics.MetricsService metrics;

    @BeforeEach
    void setUp() {
        apps = mock(PlatformApplicationMapper.class);
        services = mock(PlatformAppServiceMapper.class);
        PlatformAppDependencyMapper depRows = mock(PlatformAppDependencyMapper.class);
        releases = mock(PlatformAppReleaseMapper.class);
        executions = mock(PlatformAppExecutionMapper.class);
        K8sAppClient client = mock(K8sAppClient.class);
        AppDependencyService dependencies = new AppDependencyService(apps, services, depRows, releases, executions,
                mock(com.coding.data.mapper.system.PlatformMiddlewareInstanceMapper.class),
                mock(com.coding.data.mapper.system.PlatformApplicationReferenceMapper.class),
                mock(MiddlewareService.class), client, Runnable::run);
        ClusterComponentService componentFlags = mock(ClusterComponentService.class);
        when(componentFlags.flags(any())).thenReturn(Map.of());
        AppReleasePipeline pipeline = new AppReleasePipeline(apps, releases, dependencies, services,
                mock(IngressPortService.class), mock(IngressListenerSync.class));
        AppReleaseService releaseService = new AppReleaseService(apps, services, depRows, releases, executions,
                dependencies, pipeline, mock(AppReleaseExecutor.class), new ApplicationDeployCredentials(),
                new AppExecutionLease("test-instance"), client, mock(AppImageUpdates.class),
                mock(PlatformTransactionManager.class),
                new AppFeatureGate(new ComponentFeatureGuard(componentFlags), componentFlags,
                        mock(GatewayService.class)),
                mock(IngressPortService.class));
        metrics = mock(com.coding.platformapi.metrics.MetricsService.class);
        canary = new AppCanaryService(apps, services, releaseService, pipeline, metrics,
                mock(AppHostPathGuard.class));

        app = new PlatformApplication();
        app.setId("app1");
        app.setTenantId("t1");
        app.setClusterId("c1");
        app.setNamespace("demo");
        app.setSlug("shop");
        app.setLiveReleaseNo(0);
        when(apps.selectById("app1")).thenReturn(app);
        when(depRows.selectByApp("app1")).thenReturn(List.of());
        when(services.selectByApp("app1")).thenReturn(List.of());
        when(services.updateWithVersion(any())).thenReturn(1);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer tok");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        AppRenderResultDTO rendered = new AppRenderResultDTO();
        rendered.setManifest("---");
        rendered.setObjects(List.of());
        rendered.setProblems(List.of());
        when(client.render(any())).thenReturn(rendered);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static Map<String, Object> svc(String tag, String expose) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("imageRepo", "harbor.local/demo/x");
        m.put("imageTag", tag);
        m.put("port", 8080);
        m.put("replicas", 2);
        if (expose != null) {
            m.put("expose", expose);
        }
        return m;
    }

    private void live(Map<String, Map<String, Object>> liveServices) {
        app.setLiveReleaseNo(5);
        PlatformAppRelease r = new PlatformAppRelease();
        r.setReleaseNo(5);
        r.setSnapshotJson(new AppSnapshot(liveServices, List.of()).toJson());
        r.setObjectsJson("[]");
        when(releases.selectByNo("app1", 5)).thenReturn(r);
    }

    private PlatformAppService row(String key, Map<String, Object> desired) {
        PlatformAppService s = new PlatformAppService();
        s.setId(1L);
        s.setAppId("app1");
        s.setServiceKey(key);
        s.setDesiredJson(JSON.writeValueAsString(desired));
        s.setPendingDelete(false);
        s.setVersion(1);
        when(services.selectByKey("app1", key)).thenReturn(s);
        when(services.selectByApp("app1")).thenReturn(List.of(s));
        return s;
    }

    private PlatformAppRelease inserted() {
        ArgumentCaptor<PlatformAppRelease> cap = ArgumentCaptor.forClass(PlatformAppRelease.class);
        verify(releases).insert(cap.capture());
        return cap.getValue();
    }

    private static AppCanaryRequest start(String tag) {
        AppCanaryRequest r = new AppCanaryRequest();
        r.setAppId("app1");
        r.setServiceKey("api");
        if (tag != null) {
            Map<String, Object> d = svc(tag, "gateway-http");
            r.setDesired(d);
        }
        return r;
    }

    private Map<String, Object> inCanary(Map<String, Object> stable, Map<String, Object> canaryDesired) {
        return AppCanary.with(stable, new AppCanary.Spec(canaryDesired, 1, 10,
                new AppCanary.Header("x-canary", "true")));
    }

    @Test
    void startAddsCanaryBlockToTheLiveSnapshot() {
        live(Map.of("api", svc("1", "gateway-http")));
        row("api", svc("1", "gateway-http"));

        canary.start(start("2"), "bob", "t1");

        PlatformAppRelease r = inserted();
        assertThat(r.getType()).isEqualTo(AppCanaryService.TYPE_START);
        Map<String, Object> api = AppSnapshot.parse(r.getSnapshotJson()).services().get("api");
        AppCanary.Spec spec = AppCanary.of(api);
        assertThat(spec.weight()).isEqualTo(10);
        assertThat(spec.replicas()).isEqualTo(1);
        assertThat(spec.header().name()).isEqualTo("x-canary");
        assertThat(spec.desired()).containsEntry("imageTag", "2").containsEntry("port", 8080)
                .containsEntry("expose", "gateway-http");
        assertThat(AppCanary.without(api)).containsEntry("imageTag", "1");
        assertThat(r.getNote()).contains("api").contains("10%");
    }

    @Test
    void startWithoutDesiredCopiesStable() {
        live(Map.of("api", svc("1", "gateway-http")));
        row("api", svc("1", "gateway-http"));

        canary.start(start(null), "bob", "t1");

        AppCanary.Spec spec = AppCanary.of(AppSnapshot.parse(inserted().getSnapshotJson()).services().get("api"));
        assertThat(spec.desired()).containsEntry("imageTag", "1");
    }

    @Test
    void startRejects() {
        // 未上线
        assertThatThrownBy(() -> canary.start(start("2"), "bob", "t1")).hasMessageContaining("未上线");

        // 不是网关暴露
        live(Map.of("api", svc("1", null)));
        assertThatThrownBy(() -> canary.start(start("2"), "bob", "t1")).hasMessageContaining("网关");

        // 有状态
        Map<String, Object> stateful = svc("1", "gateway-http");
        stateful.put("persistenceEnabled", true);
        live(Map.of("api", stateful));
        assertThatThrownBy(() -> canary.start(start("2"), "bob", "t1")).hasMessageContaining("有状态");

        // 已在灰度
        live(Map.of("api", inCanary(svc("1", "gateway-http"), svc("2", "gateway-http"))));
        assertThatThrownBy(() -> canary.start(start("3"), "bob", "t1")).hasMessageContaining("已在灰度");

        live(Map.of("api", svc("1", "gateway-http")));
        // 端口不同
        AppCanaryRequest port = start("2");
        port.getDesired().put("port", 9090);
        assertThatThrownBy(() -> canary.start(port, "bob", "t1")).hasMessageContaining("端口");
        // 暴露方式不同
        AppCanaryRequest expose = start("2");
        expose.getDesired().put("expose", "gateway-tcp");
        assertThatThrownBy(() -> canary.start(expose, "bob", "t1")).hasMessageContaining("暴露方式");
        // 权重越界
        AppCanaryRequest weight = start("2");
        weight.setWeight(101);
        assertThatThrownBy(() -> canary.start(weight, "bob", "t1")).hasMessageContaining("0-100");

        // TCP 开 header
        live(Map.of("api", svc("1", "gateway-tcp")));
        AppCanaryRequest tcp = start(null);
        tcp.setHeaderEnabled(true);
        assertThatThrownBy(() -> canary.start(tcp, "bob", "t1")).hasMessageContaining("TCP");

        verify(releases, never()).insert(any());
    }

    @Test
    void tcpStartHasNoHeaderByDefault() {
        live(Map.of("api", svc("1", "gateway-tcp")));
        row("api", svc("1", "gateway-tcp"));

        canary.start(start(null), "bob", "t1");

        assertThat(AppCanary.of(AppSnapshot.parse(inserted().getSnapshotJson()).services().get("api")).header())
                .isNull();
    }

    @Test
    void updateChangesOnlyGivenFields() {
        live(Map.of("api", inCanary(svc("1", "gateway-http"), svc("2", "gateway-http"))));
        row("api", svc("1", "gateway-http"));
        AppCanaryRequest req = new AppCanaryRequest();
        req.setAppId("app1");
        req.setServiceKey("api");
        req.setWeight(50);

        canary.update(req, "bob", "t1");

        PlatformAppRelease r = inserted();
        assertThat(r.getType()).isEqualTo(AppCanaryService.TYPE_UPDATE);
        AppCanary.Spec spec = AppCanary.of(AppSnapshot.parse(r.getSnapshotJson()).services().get("api"));
        assertThat(spec.weight()).isEqualTo(50);
        assertThat(spec.desired()).containsEntry("imageTag", "2");
        assertThat(spec.header()).isEqualTo(new AppCanary.Header("x-canary", "true"));
        assertThat(spec.replicas()).isEqualTo(1);
    }

    @Test
    void updateCanTurnHeaderOff() {
        live(Map.of("api", inCanary(svc("1", "gateway-http"), svc("2", "gateway-http"))));
        row("api", svc("1", "gateway-http"));
        AppCanaryRequest req = new AppCanaryRequest();
        req.setAppId("app1");
        req.setServiceKey("api");
        req.setHeaderEnabled(false);

        canary.update(req, "bob", "t1");

        assertThat(AppCanary.of(AppSnapshot.parse(inserted().getSnapshotJson()).services().get("api")).header())
                .isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void promoteWritesCanaryDesiredBackToStableAndDropsCanary() {
        Map<String, Object> canaryDesired = svc("2", "gateway-http");
        live(Map.of("api", inCanary(svc("1", "gateway-http"), canaryDesired)));
        row("api", svc("1", "gateway-http"));

        canary.promote("app1", "api", null, "bob", "t1");

        PlatformAppRelease r = inserted();
        assertThat(r.getType()).isEqualTo(AppCanaryService.TYPE_PROMOTE);
        Map<String, Object> api = AppSnapshot.parse(r.getSnapshotJson()).services().get("api");
        assertThat(api).doesNotContainKey(AppCanary.KEY);
        assertThat(api).isEqualTo(JSON.convertValue(canaryDesired, Map.class));
        ArgumentCaptor<PlatformAppService> w = ArgumentCaptor.forClass(PlatformAppService.class);
        verify(services).updateWithVersion(w.capture());
        assertThat(JSON.readValue(w.getValue().getDesiredJson(), Map.class)).containsEntry("imageTag", "2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void abortRestoresStableRowFromLiveSnapshot() {
        Map<String, Object> stable = svc("1", "gateway-http");
        live(Map.of("api", inCanary(stable, svc("2", "gateway-http"))));
        // 全量失败后：台账已是 canary 配置
        PlatformAppService r0 = row("api", svc("2", "gateway-http"));

        canary.abort("app1", "api", null, "bob", "t1");

        PlatformAppRelease r = inserted();
        assertThat(r.getType()).isEqualTo(AppCanaryService.TYPE_ABORT);
        Map<String, Object> api = AppSnapshot.parse(r.getSnapshotJson()).services().get("api");
        assertThat(api).doesNotContainKey(AppCanary.KEY).containsEntry("imageTag", "1");
        ArgumentCaptor<PlatformAppService> w = ArgumentCaptor.forClass(PlatformAppService.class);
        verify(services).updateWithVersion(w.capture());
        Map<String, Object> written = JSON.readValue(w.getValue().getDesiredJson(), Map.class);
        assertThat(written).containsEntry("imageTag", "1");
        r0.setDesiredJson(w.getValue().getDesiredJson());
        assertThat(AppPending.compute(Map.of("api", api), List.of(r0))).isEmpty();
    }

    @Test
    void abortWithMatchingRowDoesNotWriteTheRow() {
        Map<String, Object> stable = svc("1", "gateway-http");
        live(Map.of("api", inCanary(stable, svc("2", "gateway-http"))));
        row("api", svc("1", "gateway-http"));

        canary.abort("app1", "api", null, "bob", "t1");

        inserted();
        verify(services, never()).updateWithVersion(any());
    }

    @Test
    void promoteAndAbortRejectWhenNotInCanary() {
        live(Map.of("api", svc("1", "gateway-http")));
        row("api", svc("1", "gateway-http"));

        assertThatThrownBy(() -> canary.promote("app1", "api", null, "bob", "t1")).hasMessageContaining("不在灰度");
        assertThatThrownBy(() -> canary.abort("app1", "api", null, "bob", "t1")).hasMessageContaining("不在灰度");
        AppCanaryRequest req = new AppCanaryRequest();
        req.setAppId("app1");
        req.setServiceKey("api");
        req.setWeight(20);
        assertThatThrownBy(() -> canary.update(req, "bob", "t1")).hasMessageContaining("不在灰度");
    }

    @Test
    void operationsRejectWhileAnExecutionIsRunning() {
        live(Map.of("api", inCanary(svc("1", "gateway-http"), svc("2", "gateway-http"))));
        row("api", svc("1", "gateway-http"));
        PlatformAppExecution running = new PlatformAppExecution();
        running.setStatus("RUNNING");
        when(executions.selectActiveByApp("app1")).thenReturn(running);

        assertThatThrownBy(() -> canary.promote("app1", "api", null, "bob", "t1")).hasMessageContaining("进行中");
        assertThatThrownBy(() -> canary.abort("app1", "api", null, "bob", "t1")).hasMessageContaining("进行中");
        assertThatThrownBy(() -> canary.start(start("3"), "bob", "t1")).hasMessageContaining("进行中");
    }

    @Test
    void metricsRejectsServiceNotInCanaryAndNormalizesWindow() {
        live(Map.of("api", svc("1", "gateway-http")));
        assertThatThrownBy(() -> canary.metrics("app1", "api", "15m", "t1")).hasMessageContaining("不在灰度");

        live(Map.of("api", inCanary(svc("1", "gateway-http"), svc("2", "gateway-http"))));
        when(metrics.canaryCompare("c1", "demo", "shop-api", "shop-api-canary", "15m", false, "source"))
                .thenReturn(Map.of("stable", Map.of(), "canary", Map.of()));

        var view = canary.metrics("app1", "api", "7d", "t1");

        verify(metrics).canaryCompare("c1", "demo", "shop-api", "shop-api-canary", "15m", false, "source");
        assertThat(view.window()).isEqualTo("15m");
        assertThat(view.protocol()).isEqualTo("HTTP");
    }

    // ---------- 内部灰度（2026-11-09） ----------

    private static final Map<String, Object> L7 = Map.of("mesh", Map.of("mode", "l7", "waypoint", "wp"));

    private void liveWithApp(Map<String, Map<String, Object>> liveServices, Map<String, Object> appSettings) {
        app.setLiveReleaseNo(5);
        PlatformAppRelease r = new PlatformAppRelease();
        r.setReleaseNo(5);
        r.setSnapshotJson(new AppSnapshot(liveServices, List.of(), appSettings).toJson());
        r.setObjectsJson("[]");
        when(releases.selectByNo("app1", 5)).thenReturn(r);
    }

    private static AppCanaryRequest copyStable() {
        AppCanaryRequest r = new AppCanaryRequest();
        r.setAppId("app1");
        r.setServiceKey("api");
        return r;
    }

    @Test
    void l7AppCanStartCanaryOnUnexposedService() {
        liveWithApp(Map.of("api", svc("1", null)), L7);
        row("api", svc("1", null));

        canary.start(copyStable(), "bob", "t1");

        AppCanary.Spec s = AppCanary.of(AppSnapshot.parse(inserted().getSnapshotJson()).services().get("api"));
        assertThat(s.external()).isFalse();
        assertThat(s.header()).isNotNull();
    }

    @Test
    void unexposedServiceWithoutL7IsRejected() {
        live(Map.of("api", svc("1", null)));
        assertThatThrownBy(() -> canary.start(copyStable(), "bob", "t1")).hasMessageContaining("L7");
        verify(releases, never()).insert(any());
    }

    @Test
    void externalFalseRejectedWithoutL7() {
        live(Map.of("api", svc("1", "gateway-http")));
        row("api", svc("1", "gateway-http"));
        AppCanaryRequest r = copyStable();
        r.setExternal(false);
        assertThatThrownBy(() -> canary.start(r, "bob", "t1")).hasMessageContaining("外部流量必须参与");
    }

    @Test
    void externalFalseAllowedWithL7AndKeptOnUpdate() {
        liveWithApp(Map.of("api", svc("1", "gateway-http")), L7);
        row("api", svc("1", "gateway-http"));
        AppCanaryRequest r = copyStable();
        r.setExternal(false);
        canary.start(r, "bob", "t1");
        Map<String, Object> started = AppSnapshot.parse(inserted().getSnapshotJson()).services().get("api");
        assertThat(AppCanary.of(started).external()).isFalse();

        liveWithApp(Map.of("api", started), L7);
        AppCanaryRequest u = copyStable();
        u.setWeight(50);
        canary.update(u, "bob", "t1");
        ArgumentCaptor<PlatformAppRelease> cap = ArgumentCaptor.forClass(PlatformAppRelease.class);
        verify(releases, org.mockito.Mockito.times(2)).insert(cap.capture());
        AppCanary.Spec s = AppCanary.of(AppSnapshot.parse(cap.getAllValues().get(1).getSnapshotJson()).services().get("api"));
        assertThat(s.external()).isFalse();
        assertThat(s.weight()).isEqualTo(50);
    }

    @Test
    void canaryProtocolMustMatchStable() {
        liveWithApp(Map.of("api", svc("1", null)), L7);
        row("api", svc("1", null));
        AppCanaryRequest r = copyStable();
        Map<String, Object> d = svc("2", null);
        d.put("probeType", "tcp");
        r.setDesired(d);
        assertThatThrownBy(() -> canary.start(r, "bob", "t1")).hasMessageContaining("协议必须与线上一致");
    }

    @Test
    void tcpServiceRejectsHeader() {
        Map<String, Object> tcp = svc("1", null);
        tcp.put("probeType", "tcp");
        liveWithApp(Map.of("api", tcp), L7);
        row("api", tcp);
        AppCanaryRequest on = copyStable();
        on.setHeaderEnabled(true);
        assertThatThrownBy(() -> canary.start(on, "bob", "t1")).hasMessageContaining("不支持 header 直达");

        canary.start(copyStable(), "bob", "t1");
        assertThat(AppCanary.of(AppSnapshot.parse(inserted().getSnapshotJson()).services().get("api")).header()).isNull();
    }

    @Test
    void metricsReturnsInternalOnlyForUnexposedL7Service() {
        liveWithApp(Map.of("api", inCanary(svc("1", null), svc("2", null))), L7);
        when(metrics.canaryCompare(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenReturn(Map.of("stable", Map.of("rps", 1.0), "canary", Map.of()));

        var view = canary.metrics("app1", "api", "15m", "t1");

        assertThat(view.internal()).isNotNull();
        assertThat(view.internal().stable()).containsEntry("rps", 1.0);
        assertThat(view.external()).isNull();
        verify(metrics).canaryCompare("c1", "demo", "shop-api", "shop-api-canary", "15m", false, "waypoint");
        verify(metrics, org.mockito.Mockito.times(1))
                .canaryCompare(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    void metricsReturnsExternalOnlyWithoutL7() {
        live(Map.of("api", inCanary(svc("1", "gateway-http"), svc("2", "gateway-http"))));
        when(metrics.canaryCompare(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenReturn(Map.of("stable", Map.of(), "canary", Map.of()));

        var view = canary.metrics("app1", "api", "15m", "t1");

        assertThat(view.internal()).isNull();
        assertThat(view.external()).isNotNull();
        verify(metrics).canaryCompare("c1", "demo", "shop-api", "shop-api-canary", "15m", false, "source");
        verify(metrics, org.mockito.Mockito.times(1))
                .canaryCompare(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }


    @Test
    void portlessWorkerInL7AppIsRejected() {
        Map<String, Object> worker = svc("1", null);
        worker.remove("port");
        liveWithApp(Map.of("api", worker), L7);
        row("api", worker);
        assertThatThrownBy(() -> canary.start(copyStable(), "bob", "t1")).hasMessageContaining("没有端口");
        verify(releases, never()).insert(any());
    }
}
