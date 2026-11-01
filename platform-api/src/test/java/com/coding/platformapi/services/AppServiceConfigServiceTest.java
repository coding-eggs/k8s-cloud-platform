package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.data.mapper.system.PlatformAppDependencyMapper;
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
    private AppServiceConfigService service;
    private PlatformApplication app;

    @BeforeEach
    void setUp() {
        apps = mock(PlatformApplicationMapper.class);
        services = mock(PlatformAppServiceMapper.class);
        deps = mock(PlatformAppDependencyMapper.class);
        releases = mock(PlatformAppReleaseMapper.class);
        service = new AppServiceConfigService(apps, services, deps, releases);
        app = new PlatformApplication();
        app.setId("app1");
        app.setTenantId("t1");
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
        for (String bad : List.of("Web", "1web", "web_x", "a-env", "a-secret", "a-headless", "", "x".repeat(21))) {
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
    void createRejectsPasswordFieldsAndPlainSecretLiterals() {
        Map<String, Object> d = web("1");
        d.put("password", "hunter2");
        assertThatThrownBy(() -> service.create(req("api", d), "t1")).isInstanceOf(CloudPlatformException.class);

        Map<String, Object> e = web("1");
        e.put("env", List.of(Map.of("name", "DB_PASSWORD", "kind", "LITERAL", "value", "hunter2")));
        assertThatThrownBy(() -> service.create(req("api", e), "t1")).isInstanceOf(CloudPlatformException.class);
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
    void removeNeverReleasedDeletesRow() {
        when(services.selectByKey("app1", "api")).thenReturn(row("api", web("1"), 0));
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", web("1"), 0)));
        service.remove(req("api", null), "t1");
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
        service.remove(req("api", null), "t1");
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
        assertThatThrownBy(() -> service.remove(req("api", null), "t1"))
                .isInstanceOf(CloudPlatformException.class).hasMessageContaining("cli");
        verify(services, never()).deleteById(any());
    }
}
