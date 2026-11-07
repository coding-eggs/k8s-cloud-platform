package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.data.mapper.system.PlatformAppDependencyMapper;
import com.coding.data.mapper.system.PlatformAppExecutionMapper;
import com.coding.data.models.system.PlatformAppExecution;
import com.coding.data.mapper.system.PlatformAppReleaseMapper;
import com.coding.data.mapper.system.PlatformAppServiceMapper;
import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.models.system.PlatformAppDependency;
import com.coding.data.models.system.PlatformAppRelease;
import com.coding.data.models.system.PlatformAppService;
import com.coding.data.models.system.PlatformApplication;
import com.coding.platformapi.models.AppServiceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppServiceConfigServiceTest {

    static final ObjectMapper JSON = JsonMapper.builder().build();

    private PlatformApplicationMapper apps;
    private PlatformAppServiceMapper services;
    private PlatformAppDependencyMapper deps;
    private PlatformAppReleaseMapper releases;
    private PlatformAppExecutionMapper executions;
    private AppServiceConfigService service;
    private AppFeatureGate gate;
    private PlatformApplication app;
    private com.coding.platformapi.registry.HarborInstallLocator harbor;

    @BeforeEach
    void setUp() {
        apps = mock(PlatformApplicationMapper.class);
        services = mock(PlatformAppServiceMapper.class);
        deps = mock(PlatformAppDependencyMapper.class);
        releases = mock(PlatformAppReleaseMapper.class);
        executions = mock(PlatformAppExecutionMapper.class);
        harbor = mock(com.coding.platformapi.registry.HarborInstallLocator.class);
        gate = mock(AppFeatureGate.class);
        service = new AppServiceConfigService(apps, services, deps, releases, executions, harbor, gate);
        app = new PlatformApplication();
        app.setId("app1");
        app.setTenantId("t1");
        app.setClusterId("c1");
        app.setNamespace("demo");
        app.setSlug("shop");
        app.setLiveReleaseNo(0);
        when(apps.selectById("app1")).thenReturn(app);
    }

    static Map<String, Object> web(String tag) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("imageRepo", "harbor.local/demo/web");
        m.put("imageTag", tag);
        m.put("port", 8080);
        m.put("replicas", 2);
        m.put("env", List.of(Map.of("name", "MODE", "kind", "LITERAL", "value", "info")));
        return m;
    }

    private AppServiceRequest req(String key, Map<String, Object> desired) {
        AppServiceRequest r = new AppServiceRequest();
        r.setAppId("app1");
        r.setServiceKey(key);
        r.setDesired(desired);
        return r;
    }

    private PlatformAppService row(String key, Map<String, Object> desired, int version) {
        PlatformAppService s = new PlatformAppService();
        s.setId(7L);
        s.setAppId("app1");
        s.setServiceKey(key);
        s.setDesiredJson(JSON.writeValueAsString(desired));
        s.setPendingDelete(false);
        s.setVersion(version);
        return s;
    }

    @Test
    void createRejectsBadKeys() {
        for (String bad : List.of("Web", "1web", "web_x", "a-env", "a-secret", "a-headless", "a-files", "", "x".repeat(21))) {
            assertThatThrownBy(() -> service.create(req(bad, web("1")), "t1"))
                    .as(bad).isInstanceOf(CloudPlatformException.class);
        }
        verify(services, never()).insert(any());
    }

    @Test
    void createRejectsKeyUsedByDependencyOrService() {
        PlatformAppDependency dep = new PlatformAppDependency();
        dep.setDepKey("db");
        when(deps.selectByKey("app1", "db")).thenReturn(dep);
        assertThatThrownBy(() -> service.create(req("db", web("1")), "t1"))
                .isInstanceOf(CloudPlatformException.class).hasMessageContaining("db");

        when(services.selectByKey("app1", "api")).thenReturn(row("api", web("1"), 0));
        assertThatThrownBy(() -> service.create(req("api", web("1")), "t1"))
                .isInstanceOf(CloudPlatformException.class).hasMessageContaining("api");
    }

    @Test
    void createStoresDesiredAndDerivesKindFromPort() {
        var view = service.create(req("api", web("1.0")), "t1");
        assertThat(view.kind()).isEqualTo("SERVICE");
        ArgumentCaptor<PlatformAppService> cap = ArgumentCaptor.forClass(PlatformAppService.class);
        verify(services).insert(cap.capture());
        assertThat(cap.getValue().getServiceKey()).isEqualTo("api");
        assertThat(cap.getValue().getDesiredJson()).contains("\"imageTag\":\"1.0\"");

        Map<String, Object> worker = web("1.0");
        worker.remove("port");
        assertThat(service.create(req("job", worker), "t1").kind()).isEqualTo("WORKER");
    }

    @Test
    void createRejectsUnknownPasswordField() {
        Map<String, Object> d = web("1");
        d.put("password", "hunter2");
        assertThatThrownBy(() -> service.create(req("api", d), "t1")).isInstanceOf(CloudPlatformException.class);
        verify(services, never()).insert(any());
    }

    @Test
    void credentialLikeLiteralIsSavedNotRejected() {
        String[][] cases = {{"DB_PASSWORD", "hunter2"}, {"TOKEN_TTL", "3600"},
                {"MAX_TOKENS", "4096"}, {"PASSWORD_MIN_LENGTH", "8"}};
        int i = 0;
        for (String[] kv : cases) {
            Map<String, Object> d = web("1");
            d.put("env", List.of(Map.of("name", kv[0], "kind", "LITERAL", "value", kv[1])));
            service.create(req("api" + (i++), d), "t1");
        }
        verify(services, org.mockito.Mockito.times(4)).insert(any());
    }

    @Test
    void malformedReferenceIsRejectedNotSilentlyMarkedRef() {
        for (String bad : List.of("${db.host", "${nodot}", "x${}y", "${a.b}${")) {
            Map<String, Object> d = web("1");
            d.put("env", List.of(Map.of("name", "X", "kind", "LITERAL", "value", bad)));
            assertThatThrownBy(() -> service.create(req("api", d), "t1")).as(bad)
                    .isInstanceOf(CloudPlatformException.class);
        }
        verify(services, never()).insert(any());
    }

    @Test
    void createAllowsPasswordReferencedFromDependency() {
        Map<String, Object> e = web("1");
        e.put("env", List.of(Map.of("name", "DB_PASSWORD", "kind", "REF", "value", "${db.password}")));
        service.create(req("api", e), "t1");
        verify(services).insert(any());
    }

    @Test
    void otherTenantIsRejected() {
        assertThatThrownBy(() -> service.create(req("api", web("1")), "t2"))
                .isInstanceOf(CloudPlatformException.class);
        verify(services, never()).insert(any());
    }

    @Test
    void updateVersionMismatchIsConflict() {
        when(services.selectByKey("app1", "api")).thenReturn(row("api", web("1"), 3));
        AppServiceRequest r = req("api", web("2"));
        r.setVersion(2);
        assertThatThrownBy(() -> service.update(r, "t1"))
                .isInstanceOf(CloudPlatformException.class).hasMessageContaining("冲突");
        verify(services, never()).updateWithVersion(any());
    }

    @Test
    void updateLostRaceIsConflict() {
        when(services.selectByKey("app1", "api")).thenReturn(row("api", web("1"), 3));
        when(services.updateWithVersion(any())).thenReturn(0);
        AppServiceRequest r = req("api", web("2"));
        r.setVersion(3);
        assertThatThrownBy(() -> service.update(r, "t1"))
                .isInstanceOf(CloudPlatformException.class).hasMessageContaining("冲突");
    }

    @Test
    void updateWritesNewDesiredWithVersion() {
        when(services.selectByKey("app1", "api")).thenReturn(row("api", web("1"), 3));
        when(services.updateWithVersion(any())).thenReturn(1);
        AppServiceRequest r = req("api", web("2"));
        r.setVersion(3);
        service.update(r, "t1");
        ArgumentCaptor<PlatformAppService> cap = ArgumentCaptor.forClass(PlatformAppService.class);
        verify(services).updateWithVersion(cap.capture());
        assertThat(cap.getValue().getVersion()).isEqualTo(3);
        assertThat(cap.getValue().getDesiredJson()).contains("\"imageTag\":\"2\"");
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateCallsGateWithStoredDesired() {
        when(services.selectByKey("app1", "api")).thenReturn(row("api", web("1"), 3));
        when(services.updateWithVersion(any())).thenReturn(1);
        Map<String, Object> next = web("2");
        next.put("autoscalingEnabled", true);
        AppServiceRequest r = req("api", next);
        r.setVersion(3);
        service.update(r, "t1");

        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(gate).assertCanEnable(org.mockito.ArgumentMatchers.eq("c1"), before.capture(), after.capture());
        assertThat(before.getValue()).containsEntry("imageTag", "1").doesNotContainKey("autoscalingEnabled");
        assertThat(after.getValue()).containsEntry("autoscalingEnabled", true);
        // waypoint 没变：不查（被删的 waypoint 不该挡住只改镜像的保存）
        verify(gate, org.mockito.Mockito.never()).assertWaypoint(any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void serviceGateSeesAppLevelTracing() {
        app.setSettingsJson("{\"tracing\":true,\"sampling\":20}");
        when(services.selectByKey("app1", "api")).thenReturn(row("api", web("1"), 3));
        when(services.updateWithVersion(any())).thenReturn(1);
        Map<String, Object> next = web("1");
        next.put("otel", Map.of("language", "java"));
        AppServiceRequest r = req("api", next);
        r.setVersion(3);
        service.update(r, "t1");
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(gate).assertCanEnable(org.mockito.ArgumentMatchers.eq("c1"), any(), after.capture());
        // 应用开着追踪 + 本服务选了语言 = 本服务开始被追踪，门禁看到的是合并后的生效配置
        assertThat((Map<String, Object>) after.getValue().get("otel"))
                .containsEntry("tracing", true).containsEntry("sampling", 20);
    }

    @Test
    void updateSettingsChecksVersionGateAndWrites() {
        app.setSettingsVersion(4);
        app.setSettingsJson("{\"tracing\":true}");
        when(apps.updateSettings(any(), any(), any(), any())).thenReturn(1);
        com.coding.platformapi.models.AppSettingsRequest r = new com.coding.platformapi.models.AppSettingsRequest();
        r.setAppId("app1");
        r.setVersion(3);
        r.setSettings(Map.of("mesh", Map.of("dataplaneMode", "ambient")));
        assertThatThrownBy(() -> service.updateSettings(r, "t1")).hasMessageContaining("冲突");
        r.setVersion(4);
        var view = service.updateSettings(r, "t1");
        assertThat(view.version()).isEqualTo(5);
        verify(gate).assertSettings(org.mockito.ArgumentMatchers.eq(app),
                org.mockito.ArgumentMatchers.eq(Map.of("tracing", true)),
                org.mockito.ArgumentMatchers.eq(Map.of("mesh", Map.of("dataplaneMode", "ambient"))));
        verify(apps).updateSettings(org.mockito.ArgumentMatchers.eq("app1"),
                org.mockito.ArgumentMatchers.eq("{\"mesh\":{\"dataplaneMode\":\"ambient\"}}"),
                org.mockito.ArgumentMatchers.eq(4), any());
    }

    @Test
    void clearingSettingsWritesNull() {
        app.setSettingsVersion(1);
        app.setSettingsJson("{\"tracing\":true}");
        when(apps.updateSettings(any(), any(), any(), any())).thenReturn(1);
        com.coding.platformapi.models.AppSettingsRequest r = new com.coding.platformapi.models.AppSettingsRequest();
        r.setAppId("app1");
        r.setVersion(1);
        r.setSettings(Map.of("tracing", false, "mesh", Map.of()));
        service.updateSettings(r, "t1");
        verify(apps).updateSettings(org.mockito.ArgumentMatchers.eq("app1"), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(1), any());
    }

    @Test
    void gateRejectionStopsTheSave() {
        when(services.selectByKey("app1", "api")).thenReturn(row("api", web("1"), 3));
        org.mockito.Mockito.doThrow(new com.coding.common.exception.CloudPlatformException(
                com.coding.common.exception.EnumResponseType.OPERATION_NOT_SUPPORTED, "无法开启自动扩缩容"))
                .when(gate).assertCanEnable(any(), any(), any());
        AppServiceRequest r = req("api", web("2"));
        r.setVersion(3);
        assertThatThrownBy(() -> service.update(r, "t1")).hasMessageContaining("自动扩缩容");
        verify(services, org.mockito.Mockito.never()).updateWithVersion(any());
    }

    @Test
    void createCallsGateWithNullBefore() {
        service.create(req("web", web("1")), "t1");
        verify(gate).assertCanEnable(org.mockito.ArgumentMatchers.eq("c1"), org.mockito.ArgumentMatchers.isNull(), any());
    }

    @Test
    void removeNeverReleasedDeletesRow() {
        when(services.selectByKey("app1", "api")).thenReturn(row("api", web("1"), 0));
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", web("1"), 0)));
        AppServiceRequest r = req("api", null);
        r.setVersion(0);
        service.remove(r, "t1");
        verify(services).deleteById(7L);
    }

    @Test
    void removeReleasedMarksPendingDelete() {
        app.setLiveReleaseNo(2);
        PlatformAppRelease live = new PlatformAppRelease();
        live.setSnapshotJson("{\"services\":{\"api\":" + JSON.writeValueAsString(web("1")) + "},\"dependencies\":[]}");
        when(releases.selectByNo("app1", 2)).thenReturn(live);
        PlatformAppService r = row("api", web("1"), 4);
        when(services.selectByKey("app1", "api")).thenReturn(r);
        when(services.selectByApp("app1")).thenReturn(List.of(r));
        when(services.updateWithVersion(any())).thenReturn(1);
        AppServiceRequest rq = req("api", null);
        rq.setVersion(4);
        service.remove(rq, "t1");
        ArgumentCaptor<PlatformAppService> cap = ArgumentCaptor.forClass(PlatformAppService.class);
        verify(services).updateWithVersion(cap.capture());
        assertThat(cap.getValue().getPendingDelete()).isTrue();
        verify(services, never()).deleteById(any());
    }

    @Test
    void removeRejectedWhileAnotherServiceReferencesIt() {
        Map<String, Object> client = web("1");
        client.put("env", List.of(Map.of("name", "API", "kind", "REF", "value", "${api.url}")));
        PlatformAppService api = row("api", web("1"), 0);
        when(services.selectByKey("app1", "api")).thenReturn(api);
        when(services.selectByApp("app1")).thenReturn(List.of(api, row("cli", client, 0)));
        AppServiceRequest rq = req("api", null);
        rq.setVersion(0);
        assertThatThrownBy(() -> service.remove(rq, "t1"))
                .isInstanceOf(CloudPlatformException.class).hasMessageContaining("cli");
        verify(services, never()).deleteById(any());
    }

    @Test
    void removeWhileFirstReleaseIsRunningMarksPendingDeleteInsteadOfDeleting() {
        // 线上还没有发布（live=0），但第一次发布正在跑，快照里已有 api
        PlatformAppExecution active = new PlatformAppExecution();
        active.setAppId("app1");
        active.setReleaseNo(1);
        active.setStatus("RUNNING");
        when(executions.selectActiveByApp("app1")).thenReturn(active);
        PlatformAppRelease first = new PlatformAppRelease();
        first.setSnapshotJson("{\"services\":{\"api\":" + JSON.writeValueAsString(web("1"))
                + "},\"dependencies\":[]}");
        when(releases.selectByNo("app1", 1)).thenReturn(first);
        PlatformAppService r = row("api", web("1"), 2);
        when(services.selectByKey("app1", "api")).thenReturn(r);
        when(services.selectByApp("app1")).thenReturn(List.of(r));
        when(services.updateWithVersion(any())).thenReturn(1);
        AppServiceRequest rq = req("api", null);
        rq.setVersion(2);

        service.remove(rq, "t1");

        ArgumentCaptor<PlatformAppService> cap = ArgumentCaptor.forClass(PlatformAppService.class);
        verify(services).updateWithVersion(cap.capture());
        assertThat(cap.getValue().getPendingDelete()).isTrue();
        verify(services, never()).deleteById(any());
    }

    @Test
    void removeWithStaleVersionIsConflict() {
        PlatformAppService r = row("api", web("1"), 5);
        when(services.selectByKey("app1", "api")).thenReturn(r);
        when(services.selectByApp("app1")).thenReturn(List.of(r));
        AppServiceRequest rq = req("api", null);
        rq.setVersion(4);
        assertThatThrownBy(() -> service.remove(rq, "t1"))
                .isInstanceOf(CloudPlatformException.class).hasMessageContaining("冲突");
        AppServiceRequest none = req("api", null);
        assertThatThrownBy(() -> service.remove(none, "t1")).isInstanceOf(CloudPlatformException.class);
        verify(services, never()).deleteById(any());
        verify(services, never()).updateWithVersion(any());
    }

    private Map<String, Object> savedDesired() {
        org.mockito.ArgumentCaptor<PlatformAppService> cap = org.mockito.ArgumentCaptor.forClass(PlatformAppService.class);
        verify(services).insert(cap.capture());
        return JSON.readValue(cap.getValue().getDesiredJson(), new tools.jackson.core.type.TypeReference<Map<String, Object>>() { });
    }

    @Test
    void saveMergesPlatformPullSecretIntoAUserSetListOnAPlatformHarborImage() {
        when(harbor.isPlatformHost("harbor.local")).thenReturn(true);
        Map<String, Object> d = web("1");
        d.put("imagePullSecrets", List.of("mine", "mine", "platform-registry-pull"));

        service.create(req("web", d), "t1");

        assertThat(savedDesired().get("imagePullSecrets")).isEqualTo(List.of("mine", "platform-registry-pull"));
    }

    @Test
    void saveLeavesNonHarborImagesAndEmptyListsAlone() {
        when(harbor.isPlatformHost("harbor.local")).thenReturn(false);
        Map<String, Object> other = web("1");
        other.put("imagePullSecrets", List.of("mine"));
        service.create(req("web", other), "t1");
        assertThat(savedDesired().get("imagePullSecrets")).isEqualTo(List.of("mine"));
    }

    @Test
    void saveDoesNotInventAListWhenTheUserSetNone() {
        when(harbor.isPlatformHost("harbor.local")).thenReturn(true);
        service.create(req("web", web("1")), "t1");
        assertThat(savedDesired()).doesNotContainKey("imagePullSecrets");
    }

    private Map<String, Object> updatedDesired() {
        ArgumentCaptor<PlatformAppService> cap = ArgumentCaptor.forClass(PlatformAppService.class);
        verify(services).updateWithVersion(cap.capture());
        return JSON.readValue(cap.getValue().getDesiredJson(), new tools.jackson.core.type.TypeReference<Map<String, Object>>() { });
    }

    @Test
    void updateAlsoMergesPlatformPullSecretIntoAUserSetList() {
        when(harbor.isPlatformHost("harbor.local")).thenReturn(true);
        when(services.selectByKey("app1", "api")).thenReturn(row("api", web("1"), 3));
        when(services.updateWithVersion(any())).thenReturn(1);
        Map<String, Object> d = web("2");
        d.put("imagePullSecrets", List.of("mine"));
        AppServiceRequest r = req("api", d);
        r.setVersion(3);

        var view = service.update(r, "t1");

        assertThat(updatedDesired().get("imagePullSecrets")).isEqualTo(List.of("mine", "platform-registry-pull"));
        assertThat(view.desired().get("imagePullSecrets")).isEqualTo(List.of("mine", "platform-registry-pull"));
    }

    @Test
    void configFilesRoundTripThroughUpdateAndTheReturnedView() {
        when(services.selectByKey("app1", "api")).thenReturn(row("api", web("1"), 3));
        when(services.updateWithVersion(any())).thenReturn(1);
        Map<String, Object> d = web("2");
        List<Map<String, Object>> files = List.of(Map.of("path", "/etc/app/app.yaml", "content", "db: ${db.host}\n"));
        d.put("configFiles", files);
        AppServiceRequest r = req("api", d);
        r.setVersion(3);

        var view = service.update(r, "t1");

        assertThat(updatedDesired().get("configFiles")).isEqualTo(files);
        assertThat(view.desired().get("configFiles")).isEqualTo(files);
    }
}
