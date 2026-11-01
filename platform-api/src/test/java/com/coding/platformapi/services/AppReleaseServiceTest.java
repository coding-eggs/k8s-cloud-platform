package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.app.AppRenderRequestDTO;
import com.coding.common.models.app.AppRenderResultDTO;
import com.coding.common.models.middleware.MiddlewareConnectionDTO;
import com.coding.common.models.middleware.MiddlewareConnectionDTO.ConnInfo;
import com.coding.data.mapper.system.PlatformAppDependencyMapper;
import com.coding.data.mapper.system.PlatformAppExecutionMapper;
import com.coding.data.mapper.system.PlatformAppReleaseMapper;
import com.coding.data.mapper.system.PlatformAppServiceMapper;
import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.models.system.PlatformAppDependency;
import com.coding.data.models.system.PlatformAppExecution;
import com.coding.data.models.system.PlatformAppRelease;
import com.coding.data.models.system.PlatformAppService;
import com.coding.data.models.system.PlatformApplication;
import com.coding.platformapi.k8s.K8sAppClient;
import com.coding.platformapi.models.AppReleasePreviewView;
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

class AppReleaseServiceTest {

    static final ObjectMapper JSON = JsonMapper.builder().build();

    private PlatformApplicationMapper apps;
    private PlatformAppServiceMapper services;
    private PlatformAppDependencyMapper depRows;
    private PlatformAppReleaseMapper releases;
    private PlatformAppExecutionMapper executions;
    private MiddlewareService middleware;
    private AppReleaseExecutor executor;
    private ApplicationDeployCredentials credentials;
    private K8sAppClient client;
    private AppReleaseService service;
    private AppReleasePipeline pipeline;
    private PlatformApplication app;

    @BeforeEach
    void setUp() {
        apps = mock(PlatformApplicationMapper.class);
        services = mock(PlatformAppServiceMapper.class);
        depRows = mock(PlatformAppDependencyMapper.class);
        releases = mock(PlatformAppReleaseMapper.class);
        executions = mock(PlatformAppExecutionMapper.class);
        middleware = mock(MiddlewareService.class);
        executor = mock(AppReleaseExecutor.class);
        credentials = new ApplicationDeployCredentials();
        client = mock(K8sAppClient.class);
        AppDependencyService dependencies = new AppDependencyService(depRows, middleware);
        pipeline = new AppReleasePipeline(apps, releases, dependencies);
        service = new AppReleaseService(apps, services, depRows, releases, executions, dependencies,
                pipeline, executor, credentials, client, mock(PlatformTransactionManager.class));
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

    static Map<String, Object> svc(String tag, String... envRows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("imageRepo", "harbor.local/demo/x");
        m.put("imageTag", tag);
        m.put("port", 8080);
        m.put("replicas", 1);
        List<Map<String, Object>> env = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < envRows.length; i += 2) {
            env.add(Map.of("name", envRows[i], "kind", "LITERAL", "value", envRows[i + 1]));
        }
        m.put("env", env);
        return m;
    }

    private PlatformAppService row(String key, Map<String, Object> desired, boolean pendingDelete) {
        PlatformAppService s = new PlatformAppService();
        s.setId((long) key.hashCode());
        s.setAppId("app1");
        s.setServiceKey(key);
        s.setDesiredJson(JSON.writeValueAsString(desired));
        s.setPendingDelete(pendingDelete);
        s.setVersion(1);
        return s;
    }

    private void live(int no, Map<String, Map<String, Object>> liveServices) {
        app.setLiveReleaseNo(no);
        PlatformAppRelease r = new PlatformAppRelease();
        r.setReleaseNo(no);
        r.setSnapshotJson(JSON.writeValueAsString(Map.of("services", liveServices, "dependencies", List.of())));
        r.setObjectsJson("[]");
        when(releases.selectByNo("app1", no)).thenReturn(r);
    }

    private String createdSnapshot() {
        ArgumentCaptor<PlatformAppRelease> cap = ArgumentCaptor.forClass(PlatformAppRelease.class);
        verify(releases).insert(cap.capture());
        return cap.getValue().getSnapshotJson();
    }

    @Test
    void unchangedDesiredIsNotPending() {
        live(1, Map.of("api", svc("1")));
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("1"), false)));
        AppReleasePreviewView v = service.preview("app1", List.of(), Map.of(), "t1");
        assertThat(v.pendingKeys()).isEmpty();
    }

    @Test
    void changedAndNewServicesArePending() {
        live(1, Map.of("api", svc("1")));
        when(services.selectByApp("app1")).thenReturn(List.of(
                row("api", svc("2"), false), row("job", svc("1"), false)));
        AppReleasePreviewView v = service.preview("app1", List.of(), Map.of(), "t1");
        assertThat(v.pendingKeys()).containsExactlyInAnyOrder("api", "job");
        assertThat(v.changes()).anySatisfy(c -> {
            assertThat(c.key()).isEqualTo("api");
            assertThat(c.type()).isEqualTo("CHANGED");
            assertThat(c.fields()).contains("imageTag");
        });
    }

    @Test
    void snapshotIsLivePlusSelectedMinusDeletedAndRestStayPending() {
        live(3, Map.of("api", svc("1"), "old", svc("1"), "keep", svc("1")));
        when(services.selectByApp("app1")).thenReturn(List.of(
                row("api", svc("2"), false),
                row("old", svc("1"), true),
                row("keep", svc("9"), false)));
        when(releases.selectMaxNo("app1")).thenReturn(3);

        service.create("app1", List.of("api", "old"), Map.of(), "note", "bob", "t1");

        String snap = createdSnapshot();
        var services = JSON.readTree(snap).get("services");
        assertThat(services.has("old")).isFalse();
        assertThat(services.get("api").get("imageTag").asText()).isEqualTo("2");
        assertThat(services.get("keep").get("imageTag").asText()).isEqualTo("1");   // 未勾选：仍是线上版本
    }

    @Test
    void editingDesiredAfterCreateDoesNotChangeSnapshot() {
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("1"), false)));
        when(releases.selectMaxNo("app1")).thenReturn(null);
        service.create("app1", List.of("api"), Map.of(), null, "bob", "t1");
        String snap = createdSnapshot();
        // 发布中用户又改了 desired：已落库的快照是字符串，不会跟着变
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("2"), false)));
        assertThat(snap).contains("\"imageTag\":\"1\"").doesNotContain("\"imageTag\":\"2\"");
    }

    @Test
    void selectingServiceThatReferencesUnreleasedServiceListsKeysToAdd() {
        Map<String, Object> web = svc("1", "BACKEND", "${api.url}");
        when(services.selectByApp("app1")).thenReturn(List.of(
                row("web", web, false), row("api", svc("1"), false)));
        AppReleasePreviewView v = service.preview("app1", List.of("web"), Map.of(), "t1");
        assertThat(v.requiredKeys()).containsExactly("api");

        assertThatThrownBy(() -> service.create("app1", List.of("web"), Map.of(), null, "bob", "t1"))
                .isInstanceOf(CloudPlatformException.class).hasMessageContaining("api");
        verify(releases, never()).insert(any());
    }

    @Test
    void unresolvableReferenceBlocksRelease() {
        when(services.selectByApp("app1")).thenReturn(List.of(
                row("web", svc("1", "DB", "${nope.host}"), false)));
        assertThatThrownBy(() -> service.create("app1", List.of("web"), Map.of(), null, "bob", "t1"))
                .isInstanceOf(CloudPlatformException.class).hasMessageContaining("nope");
    }

    @Test
    void precheckFailsWhenReferencedMiddlewareNotReadyAndNamesDependency() {
        PlatformAppDependency dep = new PlatformAppDependency();
        dep.setAppId("app1");
        dep.setDepKey("order-db");
        dep.setType("MIDDLEWARE");
        dep.setInstanceId("mw1");
        when(depRows.selectByApp("app1")).thenReturn(List.of(dep));
        when(middleware.statusOf("mw1")).thenReturn("CREATING");
        ConnInfo info = ConnInfo.of("mysql", "db.demo.svc", 3306).build();
        MiddlewareConnectionDTO dto = new MiddlewareConnectionDTO();
        dto.setInfo(info);
        when(middleware.connection("mw1", "t1")).thenReturn(dto);

        when(services.selectByApp("app1")).thenReturn(List.of(
                row("api", svc("1", "DB", "${order-db.host}"), false)));
        service.create("app1", List.of("api"), Map.of(), null, "bob", "t1");

        ArgumentCaptor<PlatformAppRelease> cap = ArgumentCaptor.forClass(PlatformAppRelease.class);
        verify(releases).insert(cap.capture());
        PlatformAppRelease release = cap.getValue();
        release.setReleaseNo(1);
        when(releases.selectByNo("app1", 1)).thenReturn(release);
        PlatformAppExecution exec = new PlatformAppExecution();
        exec.setAppId("app1");
        exec.setReleaseNo(1);

        assertThatThrownBy(() -> pipeline.check(exec))
                .isInstanceOf(CloudPlatformException.class).hasMessageContaining("order-db");
    }

    @Test
    void activePendingOrRunningExecutionRejectsNewRelease() {
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("1"), false)));
        for (String status : List.of("PENDING", "RUNNING")) {
            PlatformAppExecution active = new PlatformAppExecution();
            active.setId(5L);
            active.setStatus(status);
            when(executions.selectActiveByApp("app1")).thenReturn(active);
            assertThatThrownBy(() -> service.create("app1", List.of("api"), Map.of(), null, "bob", "t1"))
                    .isInstanceOf(CloudPlatformException.class);
        }
        verify(releases, never()).insert(any());
        verify(executions, never()).insert(any());
    }

    @Test
    void interruptedExecutionIsSupersededAndReleaseProceeds() {
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("1"), false)));
        PlatformAppExecution old = new PlatformAppExecution();
        old.setId(5L);
        old.setStatus("INTERRUPTED");
        old.setReleaseNo(1);
        when(executions.selectActiveByApp("app1")).thenReturn(old);
        when(releases.selectMaxNo("app1")).thenReturn(1);

        service.create("app1", List.of("api"), Map.of(), null, "bob", "t1");

        ArgumentCaptor<PlatformAppExecution> upd = ArgumentCaptor.forClass(PlatformAppExecution.class);
        verify(executions).updateById(upd.capture());
        assertThat(upd.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(upd.getValue().getMessage()).isEqualTo("被新发布取代");
        verify(executions).insert(any());
    }

    @Test
    void createWritesReleaseAndExecutionRemembersCredentialAndSetsDeploying() {
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("1"), false)));
        when(releases.selectMaxNo("app1")).thenReturn(4);
        org.mockito.Mockito.doAnswer(inv -> {
            ((PlatformAppExecution) inv.getArgument(0)).setId(88L);
            return 1;
        }).when(executions).insert(any());

        Long id = service.create("app1", List.of("api"), Map.of(), "n", "bob", "t1");

        assertThat(id).isEqualTo(88L);
        ArgumentCaptor<PlatformAppExecution> cap = ArgumentCaptor.forClass(PlatformAppExecution.class);
        verify(executions).insert(cap.capture());
        assertThat(cap.getValue().getReleaseNo()).isEqualTo(5);
        assertThat(cap.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(credentials.getExecution(88L)).isEqualTo("Bearer tok");
        ArgumentCaptor<PlatformApplication> st = ArgumentCaptor.forClass(PlatformApplication.class);
        verify(apps).updateById(st.capture());
        assertThat(st.getValue().getStatus()).isEqualTo("DEPLOYING");
    }

    @Test
    void otherTenantCannotAccess() {
        assertThatThrownBy(() -> service.preview("app1", List.of(), Map.of(), "t2"))
                .isInstanceOf(CloudPlatformException.class);
        assertThatThrownBy(() -> service.create("app1", List.of("api"), Map.of(), null, "bob", "t2"))
                .isInstanceOf(CloudPlatformException.class);
        assertThatThrownBy(() -> service.overview("app1", "t2")).isInstanceOf(CloudPlatformException.class);
        assertThatThrownBy(() -> service.activeExecution("app1", "t2")).isInstanceOf(CloudPlatformException.class);
        verify(releases, never()).insert(any());
    }

    @Test
    void resumeChecksTenantBeforeCallingExecutor() {
        PlatformAppExecution exec = new PlatformAppExecution();
        exec.setId(9L);
        exec.setAppId("app1");
        when(executions.selectById(9L)).thenReturn(exec);
        assertThatThrownBy(() -> service.resume(9L, "bob", "t2")).isInstanceOf(CloudPlatformException.class);
        verify(executor, never()).resume(any(), any());

        service.resume(9L, "bob", "t1");
        verify(executor).resume(9L, "bob");
    }

    @Test
    void snapshotNeverContainsPasswordValues() {
        PlatformAppDependency ext = new PlatformAppDependency();
        ext.setAppId("app1");
        ext.setDepKey("ext");
        ext.setType("EXTERNAL");
        ext.setExternalJson("{\"protocol\":\"mysql\",\"host\":\"h\",\"port\":3306,\"secretName\":\"s\","
                + "\"usernameKey\":\"u\",\"passwordKey\":\"p\",\"password\":\"hunter2\"}");
        when(depRows.selectByApp("app1")).thenReturn(List.of(ext));
        Map<String, Object> d = svc("1", "A", "b");
        d.put("env", List.of(Map.of("name", "DB_PASSWORD", "kind", "SECRET", "secretName", "s", "secretKey", "p")));
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", d, false)));

        service.create("app1", List.of("api"), Map.of(), null, "bob", "t1");
        String snap = createdSnapshot();
        assertThat(snap).doesNotContain("hunter2");
        assertThat(snap).contains("\"secretName\":\"s\"");
    }

    @Test
    void renderRequestResolvesReferencesAtRenderTime() {
        PlatformAppDependency dep = new PlatformAppDependency();
        dep.setAppId("app1");
        dep.setDepKey("cache");
        dep.setType("MIDDLEWARE");
        dep.setInstanceId("mw2");
        when(depRows.selectByApp("app1")).thenReturn(List.of(dep));
        MiddlewareConnectionDTO dto = new MiddlewareConnectionDTO();
        dto.setInfo(ConnInfo.of("redis", "redis.demo.svc", 6379).build());
        when(middleware.connection("mw2", "t1")).thenReturn(dto);
        Map<String, Object> d = svc("1", "REDIS", "${cache.address}");
        d.remove("port");
        when(services.selectByApp("app1")).thenReturn(List.of(row("worker", d, false)));
        service.create("app1", List.of("worker"), Map.of(), null, "bob", "t1");

        ArgumentCaptor<PlatformAppRelease> cap = ArgumentCaptor.forClass(PlatformAppRelease.class);
        verify(releases).insert(cap.capture());
        assertThat(cap.getValue().getSnapshotJson()).contains("${cache.address}");   // 快照存未解析的引用

        AppRenderRequestDTO req = pipeline.build(app, cap.getValue());
        assertThat(req.getSpec().getNodes()).hasSize(1);
        assertThat(req.getSpec().getNodes().get(0).getKind()).isEqualTo("WORKER");
        assertThat(req.getSpec().getNodes().get(0).getParams()).containsEntry("image", "harbor.local/demo/x:1");
        assertThat(req.getInjectByNode().get("worker")).anySatisfy(i -> {
            assertThat(i.getName()).isEqualTo("REDIS");
            assertThat(i.getValue()).isEqualTo("redis.demo.svc:6379");
        });
    }
}
