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
import com.coding.platformapi.app.AppSnapshot;
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
    private AppImageUpdates imageUpdates;

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
        AppDependencyService dependencies = new AppDependencyService(apps, services, depRows, releases, executions,
                mock(com.coding.data.mapper.system.PlatformMiddlewareInstanceMapper.class),
                mock(com.coding.data.mapper.system.PlatformApplicationReferenceMapper.class), middleware, client,
                Runnable::run);
        imageUpdates = mock(AppImageUpdates.class);
        pipeline = new AppReleasePipeline(apps, releases, dependencies);
        service = new AppReleaseService(apps, services, depRows, releases, executions, dependencies,
                pipeline, executor, credentials, client, imageUpdates, mock(PlatformTransactionManager.class));
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
    void releasesNeverCreateInstancesOrRunInit() {
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("1"), false)));
        when(services.selectByKey("app1", "api")).thenReturn(row("api", svc("1"), false));
        when(services.updateWithVersion(any())).thenReturn(1);
        service.create("app1", List.of("api"), Map.of(), null, "bob", "t1");
        service.create("app1", List.of("api"), Map.of("api", "2"), null, "bob", "t1");

        verify(middleware, never()).create(any(), any(), any());
        verify(client, never()).init(any());
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
        ArgumentCaptor<PlatformAppRelease> cap = ArgumentCaptor.forClass(PlatformAppRelease.class);
        verify(releases).insert(cap.capture());
        PlatformAppRelease release = cap.getValue();
        release.setReleaseNo(1);

        // 发布中用户又改了 desired（标签 2、端口、变量都变）：执行器渲染用的是快照，不是 desired
        Map<String, Object> edited = svc("2", "MODE", "debug");
        edited.put("port", 9999);
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", edited, false)));

        AppRenderRequestDTO req = pipeline.build(app, release);
        var node = req.getSpec().getNodes().get(0);
        assertThat(node.getParams()).containsEntry("image", "harbor.local/demo/x:1");
        assertThat(node.getParams()).containsEntry("port", 8080);
        assertThat(req.getInjectByNode().get("api")).noneMatch(i -> "MODE".equals(i.getName())
                && "debug".equals(i.getValue()));
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
        when(executions.supersedeInterrupted(org.mockito.ArgumentMatchers.eq(5L),
                org.mockito.ArgumentMatchers.eq("被新发布取代"), any())).thenReturn(1);

        service.create("app1", List.of("api"), Map.of(), null, "bob", "t1");

        verify(executions).supersedeInterrupted(org.mockito.ArgumentMatchers.eq(5L),
                org.mockito.ArgumentMatchers.eq("被新发布取代"), any());
        verify(executions).insert(any());
    }

    @Test
    void supersedeLosingTheRaceRejectsAndWritesNothing() {
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("1"), false)));
        PlatformAppExecution old = new PlatformAppExecution();
        old.setId(5L);
        old.setStatus("INTERRUPTED");
        when(executions.selectActiveByApp("app1")).thenReturn(old);
        when(executions.supersedeInterrupted(any(), any(), any())).thenReturn(0);   // 用户刚好点了继续

        assertThatThrownBy(() -> service.create("app1", List.of("api"), Map.of(), null, "bob", "t1"))
                .isInstanceOf(CloudPlatformException.class);
        verify(releases, never()).insert(any());
        verify(executions, never()).insert(any());
    }

    @Test
    void unreferencedNotReadyDependencyDoesNotBlockRelease() {
        PlatformAppDependency cache = new PlatformAppDependency();
        cache.setAppId("app1");
        cache.setDepKey("cache");
        cache.setType("MIDDLEWARE");
        cache.setInstanceId("mw9");
        when(depRows.selectByApp("app1")).thenReturn(List.of(cache));
        when(middleware.statusOf("mw9")).thenReturn("CREATING");
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("1", "MODE", "info"), false)));

        service.create("app1", List.of("api"), Map.of(), null, "bob", "t1");   // 不应因 cache 抛错
        ArgumentCaptor<PlatformAppRelease> cap = ArgumentCaptor.forClass(PlatformAppRelease.class);
        verify(releases).insert(cap.capture());
        PlatformAppRelease release = cap.getValue();
        release.setReleaseNo(1);
        when(releases.selectByNo("app1", 1)).thenReturn(release);
        PlatformAppExecution exec = new PlatformAppExecution();
        exec.setAppId("app1");
        exec.setReleaseNo(1);

        pipeline.check(exec);   // 不抛
        verify(middleware, never()).connection(any(), any());
        verify(middleware, never()).statusOf("mw9");
    }

    @Test
    void liveOnlyServiceWithoutRowIsPendingDeleteAndListedInOverview() {
        live(1, Map.of("api", svc("1"), "ghost", svc("1")));
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("1"), false)));

        AppReleasePreviewView v = service.preview("app1", List.of("ghost"), Map.of(), "t1");
        assertThat(v.pendingKeys()).containsExactly("ghost");
        assertThat(v.changes().get(0).type()).isEqualTo("DELETED");
        assertThat(v.problems()).isEmpty();

        var overview = service.overview("app1", "t1");
        assertThat(overview.services()).anySatisfy(s -> {
            assertThat(s.key()).isEqualTo("ghost");
            assertThat(s.pending()).isEqualTo("DELETED");
            assertThat(s.version()).isNull();
            assertThat(s.desired()).isNotNull();
        });

        service.create("app1", List.of("ghost"), Map.of(), null, "bob", "t1");
        assertThat(JSON.readTree(createdSnapshot()).get("services").has("ghost")).isFalse();
    }

    @Test
    void overviewCarriesDesiredAndVersionAndNotReleased() {
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("1"), false)));
        var overview = service.overview("app1", "t1");
        var s = overview.services().get(0);
        assertThat(s.state()).isEqualTo("NOT_RELEASED");
        assertThat(s.version()).isEqualTo(1);
        assertThat(s.desired()).containsEntry("imageTag", "1");
    }

    @Test
    void liveReleaseChangedInsideTransactionIsRejected() {
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("1"), false)));
        PlatformApplication moved = new PlatformApplication();
        moved.setId("app1");
        moved.setTenantId("t1");
        moved.setLiveReleaseNo(7);
        when(apps.selectById("app1")).thenReturn(app, moved);   // 第一次校验归属，第二次事务内重读

        assertThatThrownBy(() -> service.create("app1", List.of("api"), Map.of(), null, "bob", "t1"))
                .isInstanceOf(CloudPlatformException.class).hasMessageContaining("线上已变化");
        verify(releases, never()).insert(any());
        verify(executions, never()).insert(any());
    }

    @Test
    void imageOverrideWorksWhenDesiredEqualsLive() {
        live(1, Map.of("api", svc("1.4.2")));
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("1.4.2"), false)));
        when(services.updateWithVersion(any())).thenReturn(1);

        AppReleasePreviewView v = service.preview("app1", List.of("api"), Map.of("api", "1.4.3"), "t1");
        assertThat(v.problems()).isEmpty();
        assertThat(v.changes()).singleElement().satisfies(c -> {
            assertThat(c.type()).isEqualTo("CHANGED");
            assertThat(c.fields()).contains("imageTag");
            assertThat(c.fromImage()).endsWith(":1.4.2");
            assertThat(c.toImage()).endsWith(":1.4.3");
        });
        verify(services, never()).updateWithVersion(any());   // 预览不落库

        service.create("app1", List.of("api"), Map.of("api", "1.4.3"), null, "bob", "t1");
        ArgumentCaptor<PlatformAppService> cap = ArgumentCaptor.forClass(PlatformAppService.class);
        verify(services).updateWithVersion(cap.capture());
        assertThat(cap.getValue().getDesiredJson()).contains("\"imageTag\":\"1.4.3\"");
        assertThat(cap.getValue().getVersion()).isEqualTo(1);
        assertThat(createdSnapshot()).contains("\"imageTag\":\"1.4.3\"");
    }

    @Test
    void imageOverrideLosingOptimisticLockIsConflictAndNoRelease() {
        live(1, Map.of("api", svc("1.4.2")));
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("1.4.2"), false)));
        when(services.updateWithVersion(any())).thenReturn(0);

        assertThatThrownBy(() -> service.create("app1", List.of("api"), Map.of("api", "1.4.3"), null, "bob", "t1"))
                .isInstanceOf(CloudPlatformException.class).hasMessageContaining("冲突");
        verify(releases, never()).insert(any());
    }

    @Test
    void imageOverrideForUnselectedServiceIsAnError() {
        live(1, Map.of("api", svc("1")));
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("1"), false)));
        assertThatThrownBy(() -> service.create("app1", List.of(), Map.of("api", "2"), null, "bob", "t1"))
                .isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void credentialsAreForgottenWhenTransactionBodyFails() {
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("1"), false)));
        org.mockito.Mockito.doAnswer(inv -> {
            ((PlatformAppExecution) inv.getArgument(0)).setId(55L);
            return 1;
        }).when(executions).insert(any());
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(apps).updateById(any());

        assertThatThrownBy(() -> service.create("app1", List.of("api"), Map.of(), null, "bob", "t1"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(credentials.getExecution(55L)).isNull();
    }

    @Test
    void credentialLikeLiteralWarnsInPreviewButNeverBlocks() {
        Map<String, Object> d = svc("1", "DB_PASSWORD", "hunter2", "TOKEN_TTL", "3600");
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", d, false)));

        AppReleasePreviewView v = service.preview("app1", List.of("api"), Map.of(), "t1");
        assertThat(v.warnings()).hasSize(1);
        assertThat(v.warnings().get(0)).contains("DB_PASSWORD");
        assertThat(v.problems()).isEmpty();

        service.create("app1", List.of("api"), Map.of(), null, "bob", "t1");   // 不阻断
        verify(releases).insert(any());
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

    // ---------- 回滚 ----------

    private PlatformAppRelease history(int no, String status, Map<String, Map<String, Object>> svcs,
                                       List<PlatformAppDependency> deps) {
        PlatformAppRelease r = new PlatformAppRelease();
        r.setId((long) no);
        r.setAppId("app1");
        r.setReleaseNo(no);
        r.setStatus(status);
        r.setSnapshotJson(new com.coding.platformapi.app.AppSnapshot(new java.util.TreeMap<>(svcs), deps).toJson());
        when(releases.selectByNo("app1", no)).thenReturn(r);
        return r;
    }

    private PlatformAppDependency mwDep(String key, String instanceId) {
        PlatformAppDependency d = new PlatformAppDependency();
        d.setAppId("app1");
        d.setDepKey(key);
        d.setType("MIDDLEWARE");
        d.setInstanceId(instanceId);
        return d;
    }

    private PlatformAppRelease inserted() {
        ArgumentCaptor<PlatformAppRelease> cap = ArgumentCaptor.forClass(PlatformAppRelease.class);
        verify(releases).insert(cap.capture());
        return cap.getValue();
    }

    @Test
    void rollbackCreatesRollbackReleaseFromSourceSnapshot() {
        live(3, Map.of("api", svc("2")));
        PlatformAppRelease source = history(1, "DEPLOYED", Map.of("api", svc("1")), List.of());
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("2"), false)));
        when(services.updateWithVersion(any())).thenReturn(1);
        when(releases.selectMaxNo("app1")).thenReturn(3);
        org.mockito.Mockito.doAnswer(inv -> {
            ((PlatformAppExecution) inv.getArgument(0)).setId(77L);
            return 1;
        }).when(executions).insert(any());

        Long id = service.rollback("app1", 1, "回退", "bob", "t1");

        assertThat(id).isEqualTo(77L);
        PlatformAppRelease r = inserted();
        assertThat(r.getType()).isEqualTo("ROLLBACK");
        assertThat(r.getSourceReleaseNo()).isEqualTo(1);
        assertThat(r.getReleaseNo()).isEqualTo(4);
        assertThat(r.getSnapshotJson()).isEqualTo(source.getSnapshotJson());
        assertThat(r.getNote()).isEqualTo("回退");
        ArgumentCaptor<PlatformAppExecution> ex = ArgumentCaptor.forClass(PlatformAppExecution.class);
        verify(executions).insert(ex.capture());
        assertThat(ex.getValue().getReleaseNo()).isEqualTo(4);
        assertThat(credentials.getExecution(77L)).isEqualTo("Bearer tok");
        ArgumentCaptor<PlatformApplication> st = ArgumentCaptor.forClass(PlatformApplication.class);
        verify(apps).updateById(st.capture());
        assertThat(st.getValue().getStatus()).isEqualTo("DEPLOYING");
    }

    @Test
    void rollbackSyncsUneditedServicesAndKeepsEditedOnes() {
        live(3, Map.of("api", svc("2"), "web", svc("2")));
        history(1, "DEPLOYED", Map.of("api", svc("1"), "web", svc("1")), List.of());
        when(services.selectByApp("app1")).thenReturn(List.of(
                row("api", svc("2"), false),     // 无未发布编辑：同步为旧快照
                row("web", svc("9"), false)));   // 有未发布编辑：保留
        when(services.updateWithVersion(any())).thenReturn(1);

        service.rollback("app1", 1, null, "bob", "t1");

        ArgumentCaptor<PlatformAppService> cap = ArgumentCaptor.forClass(PlatformAppService.class);
        verify(services).updateWithVersion(cap.capture());
        assertThat(cap.getValue().getServiceKey()).isEqualTo("api");
        assertThat(cap.getValue().getDesiredJson()).contains("\"imageTag\":\"1\"");
        assertThat(cap.getValue().getVersion()).isEqualTo(1);
        verify(services, never()).insert(any());
    }

    @Test
    void rollbackRebuildsRowsForServicesTheOldSnapshotHasButCurrentLacks() {
        live(3, Map.of("api", svc("2")));
        history(1, "DEPLOYED", Map.of("api", svc("1"), "job", svc("1"), "old", svc("1")), List.of());
        // job：行被硬删；old：留着已发布后被移除的待删除行
        when(services.selectByApp("app1")).thenReturn(List.of(
                row("api", svc("2"), false), row("old", svc("5"), true)));
        when(services.updateWithVersion(any())).thenReturn(1);

        service.rollback("app1", 1, null, "bob", "t1");

        ArgumentCaptor<PlatformAppService> ins = ArgumentCaptor.forClass(PlatformAppService.class);
        verify(services).insert(ins.capture());
        assertThat(ins.getValue().getServiceKey()).isEqualTo("job");
        assertThat(ins.getValue().getPendingDelete()).isFalse();
        assertThat(ins.getValue().getDesiredJson()).contains("\"imageTag\":\"1\"");
        ArgumentCaptor<PlatformAppService> upd = ArgumentCaptor.forClass(PlatformAppService.class);
        verify(services, org.mockito.Mockito.times(2)).updateWithVersion(upd.capture());
        PlatformAppService old = upd.getAllValues().stream()
                .filter(x -> "old".equals(x.getServiceKey())).findFirst().orElseThrow();
        assertThat(old.getPendingDelete()).isFalse();   // 复活：不再是待删除
        assertThat(old.getDesiredJson()).contains("\"imageTag\":\"1\"");
    }

    @Test
    void rollbackKeepsPendingDeleteOfAServiceThatIsStillLive() {
        live(3, Map.of("api", svc("2")));
        history(1, "DEPLOYED", Map.of("api", svc("1")), List.of());
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("2"), true)));

        service.rollback("app1", 1, null, "bob", "t1");

        verify(services, never()).updateWithVersion(any());   // 用户要删它的意图保留，继续显示待删除
        verify(services, never()).insert(any());
        verify(releases).insert(any());
    }

    @Test
    void rollbackRefusesWhenOldReleaseReferencesDeletedInstanceAndTouchesNothing() {
        live(3, Map.of("api", svc("2")));
        history(1, "DEPLOYED", Map.of("api", svc("1", "DB", "${db.host}")),
                List.of(mwDep("db", "mw1")));
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("2"), false)));
        when(depRows.selectByApp("app1")).thenReturn(List.of(mwDep("db", "mw1")));
        when(middleware.statusOf("mw1")).thenReturn("DELETED");

        assertThatThrownBy(() -> service.rollback("app1", 1, null, "bob", "t1"))
                .isInstanceOf(CloudPlatformException.class).hasMessageContaining("db")
                .hasMessageContaining("已删除");
        verify(releases, never()).insert(any());
        verify(executions, never()).insert(any());
        verify(services, never()).updateWithVersion(any());
        verify(services, never()).insert(any());
        verify(apps, never()).updateById(any());
    }

    @Test
    void rollbackIgnoresDeletedInstanceNobodyReferences() {
        live(3, Map.of("api", svc("2")));
        history(1, "DEPLOYED", Map.of("api", svc("1")), List.of(mwDep("cache", "mw9")));
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("2"), false)));
        when(middleware.statusOf("mw9")).thenReturn("DELETED");
        when(services.updateWithVersion(any())).thenReturn(1);

        service.rollback("app1", 1, null, "bob", "t1");
        verify(releases).insert(any());
    }

    @Test
    void rollbackRejectsBadSources() {
        live(3, Map.of("api", svc("2")));
        history(1, "FAILED", Map.of("api", svc("1")), List.of());
        history(3, "DEPLOYED", Map.of("api", svc("2")), List.of());
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("2"), false)));
        when(releases.selectMaxNo("app1")).thenReturn(3);

        assertThatThrownBy(() -> service.rollback("app1", 99, null, "bob", "t1"))
                .isInstanceOf(CloudPlatformException.class);
        assertThatThrownBy(() -> service.rollback("app1", null, null, "bob", "t1"))
                .isInstanceOf(CloudPlatformException.class);
        assertThatThrownBy(() -> service.rollback("app1", 1, null, "bob", "t1"))   // 从未成功上线
                .isInstanceOf(CloudPlatformException.class).hasMessageContaining("成功");
        assertThatThrownBy(() -> service.rollback("app1", 3, null, "bob", "t1"))   // 就是线上版本
                .isInstanceOf(CloudPlatformException.class).hasMessageContaining("线上");
        verify(releases, never()).insert(any());
    }

    @Test
    void rollbackRejectsWhileAnotherExecutionIsActive() {
        live(3, Map.of("api", svc("2")));
        history(1, "DEPLOYED", Map.of("api", svc("1")), List.of());
        PlatformAppExecution active = new PlatformAppExecution();
        active.setId(5L);
        active.setStatus("RUNNING");
        when(executions.selectActiveByApp("app1")).thenReturn(active);

        assertThatThrownBy(() -> service.rollback("app1", 1, null, "bob", "t1"))
                .isInstanceOf(CloudPlatformException.class);
        verify(releases, never()).insert(any());
    }

    @Test
    void rollbackRefusesToRebuildRowWhoseKeyIsNowADependency() {
        live(3, Map.of("api", svc("2")));
        history(1, "DEPLOYED", Map.of("api", svc("1"), "cache", svc("1")), List.of());
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("2"), false)));
        when(depRows.selectByApp("app1")).thenReturn(List.of(mwDep("cache", "mw1")));

        assertThatThrownBy(() -> service.rollback("app1", 1, null, "bob", "t1"))
                .isInstanceOf(CloudPlatformException.class).hasMessageContaining("cache");
        verify(releases, never()).insert(any());
        verify(services, never()).insert(any());
    }

    @Test
    void rollbackLosingOptimisticLockWritesNoRelease() {
        live(3, Map.of("api", svc("2")));
        history(1, "DEPLOYED", Map.of("api", svc("1")), List.of());
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("2"), false)));
        when(services.updateWithVersion(any())).thenReturn(0);

        assertThatThrownBy(() -> service.rollback("app1", 1, null, "bob", "t1"))
                .isInstanceOf(CloudPlatformException.class).hasMessageContaining("冲突");
        verify(releases, never()).insert(any());
    }

    @Test
    void rollbackSupersedesInterruptedExecutionAndForgetsCredentialOnFailure() {
        live(3, Map.of("api", svc("2")));
        history(1, "DEPLOYED", Map.of("api", svc("1")), List.of());
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("2"), false)));
        when(services.updateWithVersion(any())).thenReturn(1);
        PlatformAppExecution old = new PlatformAppExecution();
        old.setId(5L);
        old.setStatus("INTERRUPTED");
        old.setReleaseNo(4);
        when(executions.selectActiveByApp("app1")).thenReturn(old);
        when(executions.supersedeInterrupted(org.mockito.ArgumentMatchers.eq(5L), any(), any())).thenReturn(1);
        org.mockito.Mockito.doAnswer(inv -> {
            ((PlatformAppExecution) inv.getArgument(0)).setId(66L);
            return 1;
        }).when(executions).insert(any());
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(apps).updateById(any());

        assertThatThrownBy(() -> service.rollback("app1", 1, null, "bob", "t1"))
                .isInstanceOf(IllegalStateException.class);
        verify(executions).supersedeInterrupted(org.mockito.ArgumentMatchers.eq(5L), any(), any());
        assertThat(credentials.getExecution(66L)).isNull();
    }

    @Test
    void rollbackOtherTenantCannotAccess() {
        assertThatThrownBy(() -> service.rollback("app1", 1, null, "bob", "t2"))
                .isInstanceOf(CloudPlatformException.class);
        verify(releases, never()).insert(any());
    }

    // ---------- 回滚：第一轮修复 ----------

    private static final String TAG_2 = "\"imageTag\":\"2\"";

    @Test
    void rollbackToLiveReleaseIsAllowedWhenANewerReleaseFailed() {
        live(3, Map.of("api", svc("2")));
        history(3, "DEPLOYED", Map.of("api", svc("2")), List.of());
        history(4, "FAILED", Map.of("api", svc("3")), List.of());   // 4 在 HEALTH 失败，线上仍是 3
        when(releases.selectMaxNo("app1")).thenReturn(4);
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("3"), false)));   // desired = 失败的 4
        when(services.updateWithVersion(any())).thenReturn(1);

        service.rollback("app1", 3, null, "bob", "t1");

        PlatformAppRelease r = inserted();
        assertThat(r.getType()).isEqualTo("ROLLBACK");
        assertThat(r.getSourceReleaseNo()).isEqualTo(3);
        assertThat(r.getReleaseNo()).isEqualTo(5);
    }

    @Test
    void desiredEqualToLatestReleaseSnapshotCountsAsUnedited() {
        // 线上 3；回滚到 1 的那次发布（5）失败了，desired 已被同步成 1 的配置
        live(3, Map.of("api", svc("2")));
        history(3, "DEPLOYED", Map.of("api", svc("2")), List.of());
        history(5, "FAILED", Map.of("api", svc("1")), List.of());
        when(releases.selectMaxNo("app1")).thenReturn(5);
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("1"), false)));
        when(services.updateWithVersion(any())).thenReturn(1);

        service.rollback("app1", 3, null, "bob", "t1");

        ArgumentCaptor<PlatformAppService> cap = ArgumentCaptor.forClass(PlatformAppService.class);
        verify(services).updateWithVersion(cap.capture());
        assertThat(cap.getValue().getDesiredJson()).contains(TAG_2);   // desired 回到 3 的配置
    }

    @Test
    void genuineEditDifferentFromLiveAndLatestIsPreserved() {
        live(3, Map.of("api", svc("2")));
        history(3, "DEPLOYED", Map.of("api", svc("2")), List.of());
        history(5, "FAILED", Map.of("api", svc("1")), List.of());
        when(releases.selectMaxNo("app1")).thenReturn(5);
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("9"), false)));

        service.rollback("app1", 3, null, "bob", "t1");

        verify(services, never()).updateWithVersion(any());
        verify(releases).insert(any());
    }

    @Test
    void rollbackRejectsWhenReferencedDependencyWasRemoved() {
        live(3, Map.of("api", svc("2")));
        history(1, "DEPLOYED", Map.of("api", svc("1", "DB", "${db.host}")), List.of(mwDep("db", "mw1")));
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("2"), false)));
        when(depRows.selectByApp("app1")).thenReturn(List.of());
        healthyInstance("mw1");

        assertThatThrownBy(() -> service.rollback("app1", 1, null, "bob", "t1"))
                .isInstanceOf(CloudPlatformException.class).hasMessageContaining("db")
                .hasMessageContaining("已被移除");
        verify(releases, never()).insert(any());
        verify(services, never()).updateWithVersion(any());
        verify(apps, never()).updateById(any());
    }

    @Test
    void rollbackRejectsWhenReferencedDependencyNowPointsToAnotherInstance() {
        live(3, Map.of("api", svc("2")));
        history(1, "DEPLOYED", Map.of("api", svc("1", "DB", "${db.host}")), List.of(mwDep("db", "mw1")));
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("2"), false)));
        when(depRows.selectByApp("app1")).thenReturn(List.of(mwDep("db", "mw2")));
        healthyInstance("mw1");
        healthyInstance("mw2");

        assertThatThrownBy(() -> service.rollback("app1", 1, null, "bob", "t1"))
                .isInstanceOf(CloudPlatformException.class).hasMessageContaining("db")
                .hasMessageContaining("指向不同的实例或类型");
        verify(releases, never()).insert(any());
        verify(services, never()).updateWithVersion(any());
        verify(apps, never()).updateById(any());
    }

    @Test
    void rollbackRejectsWhenReferencedDependencyChangedTypeButKeptTheInstance() {
        live(3, Map.of("api", svc("2")));
        history(1, "DEPLOYED", Map.of("api", svc("1", "DB", "${db.host}")), List.of(mwDep("db", "mw1")));
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("2"), false)));
        PlatformAppDependency external = mwDep("db", "mw1");
        external.setType("EXTERNAL");
        when(depRows.selectByApp("app1")).thenReturn(List.of(external));
        healthyInstance("mw1");

        assertThatThrownBy(() -> service.rollback("app1", 1, null, "bob", "t1"))
                .isInstanceOf(CloudPlatformException.class).hasMessageContaining("db")
                .hasMessageContaining("指向不同的实例或类型");
        verify(releases, never()).insert(any());
        verify(services, never()).updateWithVersion(any());
        verify(apps, never()).updateById(any());
    }

    @Test
    void rollbackSucceedsWhenReferencedDependencyStillMatches() {
        live(3, Map.of("api", svc("2")));
        history(1, "DEPLOYED", Map.of("api", svc("1", "DB", "${db.host}")), List.of(mwDep("db", "mw1")));
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("2"), false)));
        when(depRows.selectByApp("app1")).thenReturn(List.of(mwDep("db", "mw1")));
        when(services.updateWithVersion(any())).thenReturn(1);
        healthyInstance("mw1");

        service.rollback("app1", 1, null, "bob", "t1");

        assertThat(inserted().getSourceReleaseNo()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ Harbor：新版本提示与拉取凭据

    @Test
    void overviewOffersNewTagOnlyForDeployedServicesAndOnlyProbesThem() {
        live(1, Map.of("api", svc("1")));
        when(services.selectByApp("app1")).thenReturn(List.of(
                row("api", svc("1"), false), row("job", svc("5"), false)));
        when(imageUpdates.newTags(any(), org.mockito.ArgumentMatchers.eq("t1")))
                .thenReturn(Map.of("api", "2"));

        var overview = service.overview("app1", "t1");

        assertThat(overview.services()).filteredOn(s -> s.key().equals("api"))
                .singleElement().satisfies(s -> assertThat(s.newTag()).isEqualTo("2"));
        assertThat(overview.services()).filteredOn(s -> s.key().equals("job"))
                .singleElement().satisfies(s -> assertThat(s.newTag()).isNull());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, AppImageUpdates.Probe>> cap = ArgumentCaptor.forClass(Map.class);
        verify(imageUpdates).newTags(cap.capture(), any());
        // 探的是线上的镜像（线上 tag 1）；未发布的 job 不探
        assertThat(cap.getValue()).containsOnlyKeys("api");
        assertThat(cap.getValue().get("api")).isEqualTo(new AppImageUpdates.Probe("harbor.local/demo/x", "1"));
    }

    @Test
    void overviewNeverFailsBecauseOfTheNewTagLookup() {
        live(1, Map.of("api", svc("1")));
        when(services.selectByApp("app1")).thenReturn(List.of(row("api", svc("1"), false)));
        when(imageUpdates.newTags(any(), any())).thenThrow(new IllegalStateException("harbor exploded"));

        var overview = service.overview("app1", "t1");

        assertThat(overview.services()).hasSize(1);
        assertThat(overview.services().get(0).newTag()).isNull();
    }

    @SafeVarargs
    private AppSnapshot snapshotOf(Map<String, Object>... svcs) {
        Map<String, Object> all = new LinkedHashMap<>();
        for (int i = 0; i < svcs.length; i++) {
            all.put("s" + i, svcs[i]);
        }
        return AppSnapshot.parse(JSON.writeValueAsString(Map.of("services", all, "dependencies", List.of())));
    }

    private static Object pullSecretsOf(AppRenderRequestDTO req) {
        return req.getSpec().getNodes().get(0).getParams().get("imagePullSecrets");
    }

    @Test
    void renderTakesImagePullSecretsOnlyFromTheSnapshot() {
        Map<String, Object> custom = svc("1");
        custom.put("imagePullSecrets", List.of("mine"));

        assertThat(pullSecretsOf(pipeline.buildRequest(app, 1, snapshotOf(svc("1"))))).isNull();
        assertThat(pullSecretsOf(pipeline.buildRequest(app, 1, snapshotOf(custom)))).isEqualTo(List.of("mine"));
    }

    @Test
    void anUnselectedLiveServiceRendersIdenticalParamsAcrossReleases() {
        // 平台 Harbor 镜像、没设 imagePullSecrets：渲染不得因 Harbor 状态而多出东西（否则未勾选的服务也会被重启）
        Map<String, Object> d = svc("1");
        AppRenderRequestDTO r1 = pipeline.buildRequest(app, 1, snapshotOf(d));
        AppRenderRequestDTO r2 = pipeline.buildRequest(app, 2, snapshotOf(d));

        assertThat(r2.getSpec().getNodes().get(0).getParams()).isEqualTo(r1.getSpec().getNodes().get(0).getParams());
        assertThat(r1.getSpec().getNodes().get(0).getParams()).doesNotContainKey("imagePullSecrets");
    }

    @Test
    void rollbackRendersTheOldSnapshotsListExactly() {
        Map<String, Object> d = svc("1");
        d.put("imagePullSecrets", List.of("mine", "platform-registry-pull"));
        PlatformAppRelease old = new PlatformAppRelease();
        old.setReleaseNo(3);
        old.setSnapshotJson(JSON.writeValueAsString(
                Map.of("services", Map.of("api", d), "dependencies", List.of())));

        assertThat(pullSecretsOf(pipeline.build(app, old))).isEqualTo(List.of("mine", "platform-registry-pull"));
    }

    private void healthyInstance(String instanceId) {
        when(middleware.statusOf(instanceId)).thenReturn("READY");
        MiddlewareConnectionDTO dto = new MiddlewareConnectionDTO();
        dto.setInfo(ConnInfo.of("mysql", "db.demo.svc", 3306).build());
        when(middleware.connection(instanceId, "t1")).thenReturn(dto);
    }

    // ---------------------------------------------------------------- 配置文件

    @Test
    @SuppressWarnings("unchecked")
    void renderResolvesConfigFileReferencesIntoParams() {
        Map<String, Object> d = svc("1");
        d.put("configFiles", List.of(Map.of("path", "/etc/app.yaml", "content", "peer: ${s1.url}\n")));
        Map<String, Object> peer = svc("1");

        AppRenderRequestDTO req = pipeline.buildRequest(app, 1, snapshotOf(d, peer));

        assertThat((List<Map<String, Object>>) req.getSpec().getNodes().get(0).getParams().get("configFiles"))
                .containsExactly(Map.of("path", "/etc/app.yaml", "content", "peer: http://shop-s1:8080\n"));
        assertThat(req.getSpec().getNodes().get(1).getParams()).doesNotContainKey("configFiles");
    }

    @Test
    void renderRejectsAConfigFileThatReferencesACredentialEvenIfItSlippedIntoASnapshot() {
        PlatformAppDependency dep = new PlatformAppDependency();
        dep.setAppId("app1");
        dep.setDepKey("db");
        dep.setType("MIDDLEWARE");
        dep.setInstanceId("mw1");
        MiddlewareConnectionDTO dto = new MiddlewareConnectionDTO();
        dto.setInfo(ConnInfo.of("mysql", "db.demo.svc", 3306).secret("db-sec", "user", "pass").build());
        when(middleware.connection("mw1", "t1")).thenReturn(dto);
        Map<String, Object> d = svc("1");
        d.put("configFiles", List.of(Map.of("path", "/etc/app.yaml", "content", "pw: ${db.password}")));
        AppSnapshot snapshot = AppSnapshot.parse(JSON.writeValueAsString(Map.of(
                "services", Map.of("api", d), "dependencies", List.of(Map.of(
                        "key", "db", "type", "MIDDLEWARE", "instanceId", "mw1")))));

        assertThatThrownBy(() -> pipeline.buildRequest(app, 1, snapshot))
                .isInstanceOf(CloudPlatformException.class).hasMessageContaining("${db.password}");
        assertThat(pipeline.problems(app, snapshot)).anySatisfy(p -> assertThat(p).contains("api"));
    }

    // ---- 旧模型应用只读 ----

    @Test
    void legacyAppRejectsReleaseRollbackResume() {
        app.setSpecJson("{\"nodes\":[]}");
        PlatformAppExecution exec = new PlatformAppExecution();
        exec.setId(9L);
        exec.setAppId("app1");
        when(executions.selectById(9L)).thenReturn(exec);

        assertThatThrownBy(() -> service.create("app1", List.of("web"), Map.of(), null, "bob", "t1")).hasMessage("旧模型应用只能删除，请删除后按新方式重建");
        assertThatThrownBy(() -> service.rollback("app1", 1, null, "bob", "t1")).hasMessage("旧模型应用只能删除，请删除后按新方式重建");
        assertThatThrownBy(() -> service.resume(9L, "bob", "t1")).hasMessage("旧模型应用只能删除，请删除后按新方式重建");
        verify(executor, never()).resume(any(), any());
        verify(releases, never()).insert(any(com.coding.data.models.system.PlatformAppRelease.class));
        verify(executions, never()).insert(any(PlatformAppExecution.class));
    }

    @Test
    void overviewMarksLegacyApp() {
        assertThat(service.overview("app1", "t1").legacy()).isFalse();
        app.setSpecJson("{\"nodes\":[]}");
        assertThat(service.overview("app1", "t1").legacy()).isTrue();
    }
}
