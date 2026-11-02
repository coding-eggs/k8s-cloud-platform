package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.middleware.MiddlewareConnectionDTO;
import com.coding.common.models.middleware.MiddlewareConnectionDTO.ConnInfo;
import com.coding.data.mapper.system.PlatformAppDependencyMapper;
import com.coding.data.mapper.system.PlatformAppExecutionMapper;
import com.coding.data.mapper.system.PlatformAppReleaseMapper;
import com.coding.data.mapper.system.PlatformAppServiceMapper;
import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.mapper.system.PlatformApplicationReferenceMapper;
import com.coding.data.mapper.system.PlatformMiddlewareInstanceMapper;
import com.coding.data.models.system.PlatformAppDependency;
import com.coding.data.models.system.PlatformAppService;
import com.coding.data.models.system.PlatformApplication;
import com.coding.data.models.system.PlatformApplicationReference;
import com.coding.data.models.system.PlatformMiddlewareInstance;
import com.coding.platformapi.k8s.K8sAppClient;
import com.coding.platformapi.models.AppDependencyRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppDependencyServiceTest {

    PlatformApplicationMapper apps;
    PlatformAppServiceMapper services;
    PlatformAppDependencyMapper deps;
    PlatformAppReleaseMapper releases;
    PlatformAppExecutionMapper executions;
    PlatformMiddlewareInstanceMapper instances;
    PlatformApplicationReferenceMapper refs;
    MiddlewareService middleware;
    K8sAppClient client;
    AppDependencyService service;
    PlatformApplication app;

    @BeforeEach
    void setUp() {
        apps = mock(PlatformApplicationMapper.class);
        services = mock(PlatformAppServiceMapper.class);
        deps = mock(PlatformAppDependencyMapper.class);
        releases = mock(PlatformAppReleaseMapper.class);
        executions = mock(PlatformAppExecutionMapper.class);
        instances = mock(PlatformMiddlewareInstanceMapper.class);
        refs = mock(PlatformApplicationReferenceMapper.class);
        middleware = mock(MiddlewareService.class);
        client = mock(K8sAppClient.class);
        service = new AppDependencyService(apps, services, deps, releases, executions, instances, refs,
                middleware, client, Runnable::run);
        app = new PlatformApplication();
        app.setId("app1");
        app.setTenantId("t1");
        app.setClusterId("c1");
        app.setNamespace("demo");
        app.setSlug("shop");
        app.setLiveReleaseNo(0);
        when(apps.selectById("app1")).thenReturn(app);
        when(deps.updateById(any())).thenReturn(1);
    }

    AppDependencyRequest req(String key) {
        AppDependencyRequest r = new AppDependencyRequest();
        r.setAppId("app1");
        r.setKey(key);
        return r;
    }

    AppDependencyRequest createReq(String key) {
        AppDependencyRequest r = req(key);
        r.setType("MIDDLEWARE");
        r.setSource("CREATE");
        r.setCode("mysql");
        return r;
    }

    PlatformMiddlewareInstance instance(String id, String tenant, String ns, String appId) {
        PlatformMiddlewareInstance i = new PlatformMiddlewareInstance();
        i.setId(id);
        i.setTenantId(tenant);
        i.setClusterId("c1");
        i.setNamespace(ns);
        i.setCode("mysql");
        i.setAppId(appId);
        return i;
    }

    // ------------------------------------------------------------------ 新建

    @Test
    void createInsertsPlaceholderBeforeCreatingInstanceThenLinksIt() {
        when(middleware.defaultMode("mysql")).thenReturn("SINGLE");
        PlatformMiddlewareInstance created = instance("mwi_1", "t1", "demo", "app1");
        when(instances.selectLatestByAppNode("app1", "db")).thenReturn(created);

        service.add(createReq("db"), "alice", "t1");

        InOrder order = inOrder(deps, middleware, refs);
        order.verify(deps).insert(any(PlatformAppDependency.class));
        order.verify(middleware).create(any(), anyString(), anyString());
        order.verify(refs).insert(any(PlatformApplicationReference.class));
        ArgumentCaptor<com.coding.platformapi.models.MiddlewareCreateRequest> cap =
                ArgumentCaptor.forClass(com.coding.platformapi.models.MiddlewareCreateRequest.class);
        verify(middleware).create(cap.capture(), anyString(), anyString());
        assertThat(cap.getValue().getAppId()).isEqualTo("app1");
        assertThat(cap.getValue().getAppNodeId()).isEqualTo("db");
        assertThat(cap.getValue().getClusterId()).isEqualTo("c1");
        assertThat(cap.getValue().getNamespace()).isEqualTo("demo");
        ArgumentCaptor<PlatformAppDependency> upd = ArgumentCaptor.forClass(PlatformAppDependency.class);
        verify(deps).updateById(upd.capture());
        assertThat(upd.getValue().getInstanceId()).isEqualTo("mwi_1");
        ArgumentCaptor<PlatformApplicationReference> ref = ArgumentCaptor.forClass(PlatformApplicationReference.class);
        verify(refs).insert(ref.capture());
        assertThat(ref.getValue().getRefId()).isEqualTo("mwi_1");
        assertThat(ref.getValue().getNodeId()).isEqualTo("db");
    }

    @Test
    void duplicateKeyCreatesInstanceOnlyOnce() {
        when(middleware.defaultMode("mysql")).thenReturn("SINGLE");
        when(instances.selectLatestByAppNode("app1", "db")).thenReturn(instance("mwi_1", "t1", "demo", "app1"));
        when(deps.insert(any())).thenReturn(1).thenThrow(new DuplicateKeyException("uk_app_dep"));

        service.add(createReq("db"), "alice", "t1");
        assertThatThrownBy(() -> service.add(createReq("db"), "alice", "t1"))
                .isInstanceOf(CloudPlatformException.class).hasMessageContaining("db");

        verify(middleware, org.mockito.Mockito.times(1)).create(any(), anyString(), anyString());
    }

    @Test
    void failedCreateRemovesPlaceholderSoUserCanRetry() {
        when(middleware.defaultMode("mysql")).thenReturn("SINGLE");
        when(deps.insert(any())).thenAnswer(inv -> {
            ((PlatformAppDependency) inv.getArgument(0)).setId(77L);
            return 1;
        });
        when(middleware.create(any(), anyString(), anyString()))
                .thenThrow(new CloudPlatformException(com.coding.common.exception.EnumResponseType.ERROR, "配额不足"));

        assertThatThrownBy(() -> service.add(createReq("db"), "alice", "t1"))
                .hasMessageContaining("配额不足");

        verify(deps).deleteById(77L);
        verify(refs, never()).insert(any());
    }

    // ------------------------------------------------------------------ key 规则

    @Test
    void keyUsedByServiceIncludingPendingDeleteIsRejected() {
        PlatformAppService s = new PlatformAppService();
        s.setServiceKey("api");
        s.setPendingDelete(true);
        when(services.selectByKey("app1", "api")).thenReturn(s);

        assertThatThrownBy(() -> service.add(createReq("api"), "alice", "t1"))
                .hasMessageContaining("api");
        verify(middleware, never()).create(any(), anyString(), anyString());
    }

    @Test
    void badAndReservedKeysAreRejected() {
        for (String bad : List.of("DB", "1db", "db-env", "db-secret", "db-headless", "db-files", "a".repeat(21))) {
            assertThatThrownBy(() -> service.add(createReq(bad), "alice", "t1")).as(bad)
                    .isInstanceOf(CloudPlatformException.class);
        }
    }

    @Test
    void otherTenantIsRejected() {
        assertThatThrownBy(() -> service.add(createReq("db"), "alice", "t2"))
                .isInstanceOf(CloudPlatformException.class);
        verify(deps, never()).insert(any());
    }

    // ------------------------------------------------------------------ 绑定

    AppDependencyRequest bindReq(String key, String instanceId) {
        AppDependencyRequest r = req(key);
        r.setType("MIDDLEWARE");
        r.setSource("BIND");
        r.setInstanceId(instanceId);
        return r;
    }

    @Test
    void bindWritesBoundRowAndReference() {
        when(instances.selectByPrimaryKey("mwi_9")).thenReturn(instance("mwi_9", "t1", "demo", null));

        service.add(bindReq("db", "mwi_9"), "alice", "t1");

        ArgumentCaptor<PlatformAppDependency> cap = ArgumentCaptor.forClass(PlatformAppDependency.class);
        verify(deps).insert(cap.capture());
        assertThat(cap.getValue().getOwnership()).isEqualTo("BOUND");
        assertThat(cap.getValue().getInstanceId()).isEqualTo("mwi_9");
        verify(refs).insert(any(PlatformApplicationReference.class));
        verify(middleware, never()).create(any(), anyString(), anyString());
    }

    @Test
    void bindRejectsOtherTenantNamespaceClusterAndDeleted() {
        PlatformMiddlewareInstance otherTenant = instance("a", "t2", "demo", null);
        PlatformMiddlewareInstance otherNs = instance("b", "t1", "other", null);
        PlatformMiddlewareInstance otherCluster = instance("c", "t1", "demo", null);
        otherCluster.setClusterId("c2");
        PlatformMiddlewareInstance deleted = instance("d", "t1", "demo", null);
        deleted.setDeletedAt(new java.util.Date());
        for (PlatformMiddlewareInstance i : List.of(otherTenant, otherNs, otherCluster, deleted)) {
            when(instances.selectByPrimaryKey(i.getId())).thenReturn(i);
            assertThatThrownBy(() -> service.add(bindReq("db", i.getId()), "alice", "t1"))
                    .as(i.getId()).isInstanceOf(CloudPlatformException.class);
        }
        verify(deps, never()).insert(any());
    }

    @Test
    void bindRejectsInstanceAlreadyUsedByThisApp() {
        PlatformAppDependency existing = new PlatformAppDependency();
        existing.setDepKey("db0");
        existing.setInstanceId("mwi_9");
        when(deps.selectByApp("app1")).thenReturn(List.of(existing));
        when(instances.selectByPrimaryKey("mwi_9")).thenReturn(instance("mwi_9", "t1", "demo", null));

        assertThatThrownBy(() -> service.add(bindReq("db", "mwi_9"), "alice", "t1"))
                .hasMessageContaining("db0");
    }

    // ------------------------------------------------------------------ 外部

    AppDependencyRequest externalReq(Map<String, Object> external) {
        AppDependencyRequest r = req("ext");
        r.setType("EXTERNAL");
        r.setExternal(external);
        return r;
    }

    Map<String, Object> goodExternal() {
        Map<String, Object> m = new HashMap<>();
        m.put("protocol", "mysql");
        m.put("host", "db.corp.local");
        m.put("port", 3306);
        m.put("secretName", "ext-db");
        m.put("usernameKey", "user");
        m.put("passwordKey", "pass");
        return m;
    }

    @Test
    void externalStoresAddressAndSecretReferenceOnly() {
        service.add(externalReq(goodExternal()), "alice", "t1");

        ArgumentCaptor<PlatformAppDependency> cap = ArgumentCaptor.forClass(PlatformAppDependency.class);
        verify(deps).insert(cap.capture());
        assertThat(cap.getValue().getType()).isEqualTo("EXTERNAL");
        assertThat(cap.getValue().getOwnership()).isNull();
        assertThat(cap.getValue().getExternalJson()).contains("db.corp.local").contains("ext-db");
        verify(refs, never()).insert(any());
        verify(middleware, never()).create(any(), anyString(), anyString());
    }

    @Test
    void externalRejectsPasswordAndUnknownFields() {
        for (String field : List.of("password", "passwd", "pwd", "secret", "token", "credential", "extra")) {
            Map<String, Object> m = goodExternal();
            m.put(field, "hunter2");
            assertThatThrownBy(() -> service.add(externalReq(m), "alice", "t1"))
                    .as(field).isInstanceOf(CloudPlatformException.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("hunter2"));
        }
        verify(deps, never()).insert(any());
    }

    @Test
    void externalRejectsMissingHostBadPortBadProtocolAndOrphanPasswordKey() {
        Map<String, Object> noHost = goodExternal();
        noHost.remove("host");
        Map<String, Object> badPort = goodExternal();
        badPort.put("port", 70000);
        Map<String, Object> badProto = goodExternal();
        badProto.put("protocol", "oracle");
        Map<String, Object> noSecret = goodExternal();
        noSecret.remove("secretName");
        for (Map<String, Object> m : List.of(noHost, badPort, badProto, noSecret)) {
            assertThatThrownBy(() -> service.add(externalReq(m), "alice", "t1"))
                    .isInstanceOf(CloudPlatformException.class);
        }
    }

    @Test
    void connectionsStillComeFromMiddlewareConnectionInfo() {
        PlatformAppDependency dep = new PlatformAppDependency();
        dep.setDepKey("db");
        dep.setType("MIDDLEWARE");
        dep.setInstanceId("mwi_1");
        MiddlewareConnectionDTO dto = new MiddlewareConnectionDTO();
        ConnInfo info = new ConnInfo();
        info.setHost("h");
        dto.setInfo(info);
        when(middleware.connection("mwi_1", "t1")).thenReturn(dto);
        when(deps.selectByApp("app1")).thenReturn(List.of(dep));

        assertThat(service.connections("app1", "t1")).containsKey("db");
    }

    // ------------------------------------------------------------------ 移除

    PlatformAppDependency depRow(String key, String ownership, String instanceId) {
        PlatformAppDependency d = new PlatformAppDependency();
        d.setId(5L);
        d.setAppId("app1");
        d.setDepKey(key);
        d.setType("MIDDLEWARE");
        d.setOwnership(ownership);
        d.setInstanceId(instanceId);
        return d;
    }

    PlatformAppService svcRow(String key, String envRef, boolean pendingDelete) {
        PlatformAppService s = new PlatformAppService();
        s.setServiceKey(key);
        s.setPendingDelete(pendingDelete);
        s.setDesiredJson("{\"imageRepo\":\"r\",\"imageTag\":\"1\",\"port\":80,\"replicas\":1,\"env\":["
                + "{\"name\":\"DB\",\"kind\":\"LITERAL\",\"value\":\"" + envRef + "\"}]}");
        return s;
    }

    AppDependencyRequest removeReq(String key, Boolean deleteInstance) {
        AppDependencyRequest r = req(key);
        r.setDeleteInstance(deleteInstance);
        return r;
    }

    @Test
    void removeRejectedWhileDesiredServiceReferencesItAndListsServices() {
        when(deps.selectByKey("app1", "db")).thenReturn(depRow("db", "CREATED", "mwi_1"));
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(instance("mwi_1", "t1", "demo", "app1"));
        when(services.selectByApp("app1")).thenReturn(List.of(
                svcRow("api", "${db.host}", false), svcRow("worker", "${db.port}", false),
                svcRow("web", "plain", false)));

        assertThatThrownBy(() -> service.remove(removeReq("db", null), "alice", "t1"))
                .hasMessageContaining("api").hasMessageContaining("worker")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("web"));
        verify(deps, never()).deleteById(any());
        verify(instances, never()).clearApp(anyString());
    }

    private void liveSnapshot(int no, String json) {
        app.setLiveReleaseNo(no);
        com.coding.data.models.system.PlatformAppRelease r = new com.coding.data.models.system.PlatformAppRelease();
        r.setReleaseNo(no);
        r.setSnapshotJson(json);
        when(releases.selectByNo("app1", no)).thenReturn(r);
    }

    static final String SNAP_WITH_REF = "{\"services\":{\"api\":{\"imageRepo\":\"r\",\"imageTag\":\"1\",\"port\":80,"
            + "\"replicas\":1,\"env\":[{\"name\":\"DB\",\"kind\":\"LITERAL\",\"value\":\"${db.host}\"}]}},"
            + "\"dependencies\":[]}";

    @Test
    void removeRejectedWhileLiveSnapshotReferencesIt() {
        when(deps.selectByKey("app1", "db")).thenReturn(depRow("db", "BOUND", "mwi_1"));
        liveSnapshot(3, SNAP_WITH_REF);

        assertThatThrownBy(() -> service.remove(removeReq("db", null), "alice", "t1"))
                .hasMessageContaining("api");
    }

    @Test
    void removeRejectedWhileActiveExecutionSnapshotReferencesIt() {
        when(deps.selectByKey("app1", "db")).thenReturn(depRow("db", "BOUND", "mwi_1"));
        com.coding.data.models.system.PlatformAppExecution active = new com.coding.data.models.system.PlatformAppExecution();
        active.setReleaseNo(4);
        when(executions.selectActiveByApp("app1")).thenReturn(active);
        com.coding.data.models.system.PlatformAppRelease r = new com.coding.data.models.system.PlatformAppRelease();
        r.setReleaseNo(4);
        r.setSnapshotJson(SNAP_WITH_REF);
        when(releases.selectByNo("app1", 4)).thenReturn(r);

        assertThatThrownBy(() -> service.remove(removeReq("db", null), "alice", "t1"))
                .hasMessageContaining("api");
    }

    @Test
    void removeCreatedWithDeleteCallsMiddlewareDelete() {
        when(deps.selectByKey("app1", "db")).thenReturn(depRow("db", "CREATED", "mwi_1"));
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(instance("mwi_1", "t1", "demo", "app1"));

        service.remove(removeReq("db", true), "alice", "t1");

        verify(middleware).deleteForApp("mwi_1", "app1", "alice", "t1");
        verify(instances, never()).clearApp(anyString());
        verify(refs).deleteByAppAndRef("app1", "MIDDLEWARE_INSTANCE", "mwi_1");
        verify(deps).deleteById(5L);
    }

    @Test
    void removeCreatedDefaultKeepsInstanceAndClearsItsAppSource() {
        when(deps.selectByKey("app1", "db")).thenReturn(depRow("db", "CREATED", "mwi_1"));
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(instance("mwi_1", "t1", "demo", "app1"));

        service.remove(removeReq("db", null), "alice", "t1");

        verify(instances).clearApp("mwi_1");
        verify(middleware, never()).deleteForApp(anyString(), anyString(), anyString(), anyString());
        verify(deps).deleteById(5L);
    }

    @Test
    void failedInstanceDeleteKeepsDependencyRow() {
        when(deps.selectByKey("app1", "db")).thenReturn(depRow("db", "CREATED", "mwi_1"));
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(instance("mwi_1", "t1", "demo", "app1"));
        when(middleware.deleteForApp(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new CloudPlatformException(com.coding.common.exception.EnumResponseType.ERROR, "k8s 失败"));

        assertThatThrownBy(() -> service.remove(removeReq("db", true), "alice", "t1"))
                .hasMessageContaining("k8s 失败");
        verify(deps, never()).deleteById(any());
    }

    @Test
    void removeBoundOnlyDropsTheReference() {
        when(deps.selectByKey("app1", "db")).thenReturn(depRow("db", "BOUND", "mwi_1"));

        service.remove(removeReq("db", true), "alice", "t1");

        verify(middleware, never()).deleteForApp(anyString(), anyString(), anyString(), anyString());
        verify(instances, never()).clearApp(anyString());
        verify(refs).deleteByAppAndRef("app1", "MIDDLEWARE_INSTANCE", "mwi_1");
        verify(deps).deleteById(5L);
    }

    @Test
    void deletingApplicationDetachesCreatedInstancesAndClearsReferences() {
        PlatformAppDependency created = depRow("db", "CREATED", "mwi_1");
        PlatformAppDependency bound = depRow("cache", "BOUND", "mwi_2");
        bound.setId(6L);
        when(deps.selectByApp("app1")).thenReturn(List.of(created, bound));

        service.detachForAppDelete("app1");

        // 一条语句按 app_id 转交：旧画布建的、没有依赖行的实例也在内
        verify(instances).clearByApp("app1");
        verify(instances, never()).clearApp(anyString());
        verify(deps).deleteById(5L);
        verify(deps).deleteById(6L);
        verify(refs).deleteByApp("app1");
    }

    // ------------------------------------------------------------------ 初始化

    void readyMysqlDependency(String initHash, String initStatus) {
        PlatformAppDependency d = depRow("db", "CREATED", "mwi_1");
        d.setInitHash(initHash);
        d.setInitStatus(initStatus);
        when(deps.selectByKey("app1", "db")).thenReturn(d);
        when(middleware.get("mwi_1", "t1")).thenReturn(instance("mwi_1", "t1", "demo", "app1"));
        when(middleware.statusOf("mwi_1")).thenReturn("READY");
        ConnInfo info = new ConnInfo();
        info.setHost("mw.demo.svc");
        info.setPort(3306);
        info.setSecretName("mw-secret");
        info.setPasswordKey("password");
        MiddlewareConnectionDTO dto = new MiddlewareConnectionDTO();
        dto.setInfo(info);
        when(middleware.connection("mwi_1", "t1")).thenReturn(dto);
        when(deps.startInit(anyLong(), any())).thenReturn(1);
        org.springframework.mock.web.MockHttpServletRequest http = new org.springframework.mock.web.MockHttpServletRequest();
        http.addHeader("Authorization", "Bearer tok");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(http));
    }

    @org.junit.jupiter.api.AfterEach
    void clearRequest() {
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    AppDependencyRequest initReq(Boolean confirm) {
        AppDependencyRequest r = req("db");
        r.setInitPayload("create table t(id int);");
        r.setConfirm(confirm);
        return r;
    }

    @Test
    void initRejectedWhenInstanceNotReady() {
        readyMysqlDependency(null, null);
        when(middleware.statusOf("mwi_1")).thenReturn("CREATING");

        assertThatThrownBy(() -> service.init(initReq(null), "alice", "t1")).hasMessageContaining("CREATING");
        verify(client, never()).init(any());
        verify(deps, never()).startInit(anyLong(), any());
    }

    @Test
    void initSuccessRecordsHashAndRunsUnderInitiatorToken() {
        readyMysqlDependency(null, null);
        String[] seen = new String[1];
        when(client.init(any())).thenAnswer(inv -> {
            seen[0] = com.coding.platformapi.k8s.K8sServerGateway.delegatedAuthorization();
            com.coding.common.models.app.AppInitResultDTO r = new com.coding.common.models.app.AppInitResultDTO();
            r.setOk(true);
            r.setMessage("初始化完成");
            r.setLogTail("done");
            return r;
        });

        service.init(initReq(null), "alice", "t1");

        assertThat(seen[0]).isEqualTo("Bearer tok");
        ArgumentCaptor<com.coding.common.models.app.AppInitRequestDTO> dto =
                ArgumentCaptor.forClass(com.coding.common.models.app.AppInitRequestDTO.class);
        verify(client).init(dto.capture());
        assertThat(dto.getValue().getSecretName()).isEqualTo("mw-secret");
        assertThat(dto.getValue().getPasswordKey()).isEqualTo("password");
        assertThat(dto.getValue().getJobName()).hasSizeLessThanOrEqualTo(63);
        verify(deps).finishInit(org.mockito.ArgumentMatchers.eq(5L), org.mockito.ArgumentMatchers.eq("SUCCESS"),
                org.mockito.ArgumentMatchers.matches("[0-9a-f]{64}"), org.mockito.ArgumentMatchers.contains("done"),
                any());
    }

    @Test
    void secondRunWithoutConfirmIsRejectedButWithConfirmRuns() {
        readyMysqlDependency("a".repeat(64), "SUCCESS");

        assertThatThrownBy(() -> service.init(initReq(null), "alice", "t1")).hasMessageContaining("确认");
        verify(client, never()).init(any());

        when(client.init(any())).thenReturn(new com.coding.common.models.app.AppInitResultDTO());
        service.init(initReq(true), "alice", "t1");
        verify(client).init(any());
    }

    @Test
    void concurrentInitIsRejectedWhenAlreadyRunning() {
        readyMysqlDependency(null, "RUNNING");
        when(deps.startInit(anyLong(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.init(initReq(null), "alice", "t1")).hasMessageContaining("正在初始化");
        verify(client, never()).init(any());
    }

    @Test
    void failedInitIsRecordedAsFailedWithoutHash() {
        readyMysqlDependency(null, null);
        when(client.init(any())).thenThrow(new RuntimeException("Job 超时"));

        service.init(initReq(null), "alice", "t1");

        verify(deps).finishInit(org.mockito.ArgumentMatchers.eq(5L), org.mockito.ArgumentMatchers.eq("FAILED"),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.contains("Job 超时"), any());
    }

    @Test
    void initWithoutLoginTokenOrOnExternalIsRejected() {
        readyMysqlDependency(null, null);
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        assertThatThrownBy(() -> service.init(initReq(null), "alice", "t1"))
                .isInstanceOf(CloudPlatformException.class);

        PlatformAppDependency ext = depRow("ext", null, null);
        ext.setType("EXTERNAL");
        when(deps.selectByKey("app1", "ext")).thenReturn(ext);
        AppDependencyRequest r = req("ext");
        r.setInitPayload("x");
        assertThatThrownBy(() -> service.init(r, "alice", "t1")).isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void runningInitIsFailedOnStartupRecovery() {
        service.recoverInterrupted();

        verify(deps).failRunningInit(org.mockito.ArgumentMatchers.contains("重启"),
                org.mockito.ArgumentMatchers.isNull(), any());
    }

    // ------------------------------------------------------------------ 修复轮 1

    @Test
    void createSucceedsButPlaceholderGoneHandsInstanceOverAndWritesNoReference() {
        when(middleware.defaultMode("mysql")).thenReturn("SINGLE");
        when(instances.selectLatestByAppNode("app1", "db")).thenReturn(instance("mwi_1", "t1", "demo", "app1"));
        when(deps.updateById(any())).thenReturn(0);   // 占位行已被并发移除

        assertThatThrownBy(() -> service.add(createReq("db"), "alice", "t1"))
                .hasMessageContaining("转交");

        verify(instances).clearApp("mwi_1");
        verify(refs, never()).insert(any());
    }

    @Test
    void missingLedgerRowAfterCreateDoesNotTellUserToAddAgain() {
        when(middleware.defaultMode("mysql")).thenReturn("SINGLE");
        when(instances.selectLatestByAppNode("app1", "db")).thenReturn(null);

        assertThatThrownBy(() -> service.add(createReq("db"), "alice", "t1"))
                .hasMessageContaining("不要重新添加")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("请移除后重试"));
        verify(deps, never()).deleteById(any());
    }

    PlatformAppDependency unlinkedCreated(long ageMillis) {
        PlatformAppDependency d = depRow("db", "CREATED", null);
        d.setCreatedAt(new java.util.Date(System.currentTimeMillis() - ageMillis));
        when(deps.selectByKey("app1", "db")).thenReturn(d);
        return d;
    }

    @Test
    void removeRejectedWhileCreateIsPossiblyInFlight() {
        unlinkedCreated(10_000);

        assertThatThrownBy(() -> service.remove(removeReq("db", true), "alice", "t1"))
                .hasMessageContaining("正在创建");
        verify(deps, never()).deleteById(any());
        verify(middleware, never()).deleteForApp(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void removeResolvesAnUnlinkedCreatedInstanceByAppAndKey() {
        unlinkedCreated(10 * 60_000L);
        when(instances.selectLatestByAppNode("app1", "db")).thenReturn(instance("mwi_7", "t1", "demo", "app1"));
        when(instances.selectByPrimaryKey("mwi_7")).thenReturn(instance("mwi_7", "t1", "demo", "app1"));

        service.remove(removeReq("db", true), "alice", "t1");
        verify(middleware).deleteForApp("mwi_7", "app1", "alice", "t1");
        verify(refs).deleteByAppAndRef("app1", "MIDDLEWARE_INSTANCE", "mwi_7");
        verify(deps).deleteById(5L);

        service.remove(removeReq("db", null), "alice", "t1");
        verify(instances).clearApp("mwi_7");
    }

    @Test
    void removeUnlinkedCreatedWithNoInstanceJustDropsTheRow() {
        unlinkedCreated(10 * 60_000L);
        when(instances.selectLatestByAppNode("app1", "db")).thenReturn(null);

        service.remove(removeReq("db", true), "alice", "t1");

        verify(deps).deleteById(5L);
        verify(middleware, never()).deleteForApp(anyString(), anyString(), anyString(), anyString());
        verify(instances, never()).clearApp(anyString());
    }

    @Test
    void removeTreatsAnAlreadyDeletedInstanceAsSuccess() {
        when(deps.selectByKey("app1", "db")).thenReturn(depRow("db", "CREATED", "mwi_1"));
        PlatformMiddlewareInstance gone = instance("mwi_1", "t1", "demo", "app1");
        gone.setDeletedAt(new java.util.Date());
        when(instances.selectByPrimaryKey("mwi_1")).thenReturn(gone);

        service.remove(removeReq("db", true), "alice", "t1");

        verify(middleware, never()).deleteForApp(anyString(), anyString(), anyString(), anyString());
        verify(deps).deleteById(5L);
        verify(refs).deleteByAppAndRef("app1", "MIDDLEWARE_INSTANCE", "mwi_1");
    }

    @Test
    void removeRejectedWhileInitIsRunning() {
        PlatformAppDependency d = depRow("db", "BOUND", "mwi_1");
        d.setInitStatus("RUNNING");
        when(deps.selectByKey("app1", "db")).thenReturn(d);

        assertThatThrownBy(() -> service.remove(removeReq("db", null), "alice", "t1"))
                .hasMessageContaining("正在初始化");
        verify(deps, never()).deleteById(any());
    }

    @Test
    void externalHostMustBeAPlainHostnameOrAddress() {
        for (String bad : List.of("user:pass@db.corp", "http://db.corp", "db.corp:3306", "db corp", "db.corp/x",
                "-db", "db..corp")) {
            Map<String, Object> m = goodExternal();
            m.put("host", bad);
            assertThatThrownBy(() -> service.add(externalReq(m), "alice", "t1")).as(bad)
                    .isInstanceOf(CloudPlatformException.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(bad));
        }
        Map<String, Object> nonString = goodExternal();
        nonString.put("host", 12345);
        assertThatThrownBy(() -> service.add(externalReq(nonString), "alice", "t1"))
                .isInstanceOf(CloudPlatformException.class);
        for (String ok : List.of("10.0.0.5", "mysql.demo.svc.cluster.local", "db")) {
            Map<String, Object> m = goodExternal();
            m.put("host", ok);
            service.add(externalReq(m), "alice", "t1");
        }
    }

    @Test
    void addedBoundDependencyReportsItsRealStatusNotCreating() {
        when(instances.selectByPrimaryKey("mwi_9")).thenReturn(instance("mwi_9", "t1", "demo", null));
        when(middleware.statusOf("mwi_9")).thenReturn("READY");

        assertThat(service.add(bindReq("db", "mwi_9"), "alice", "t1").status()).isEqualTo("READY");
    }

    @Test
    void dependencyRowCarriesMiddlewareCodeAndExternalHasNone() {
        PlatformMiddlewareInstance redis = instance("mwi_9", "t1", "demo", null);
        redis.setCode("redis");
        when(instances.selectByPrimaryKey("mwi_9")).thenReturn(redis);
        when(middleware.statusOf("mwi_9")).thenReturn("READY");

        assertThat(service.add(bindReq("db", "mwi_9"), "alice", "t1").code()).isEqualTo("redis");

        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("protocol", "mysql");
        m.put("host", "db.example.com");
        assertThat(service.add(externalReq(m), "alice", "t1").code()).isNull();
    }

    // ---- ES 初始化

    void readyElasticsearch(String scheme) {
        readyMysqlDependency(null, null);
        PlatformMiddlewareInstance es = instance("mwi_1", "t1", "demo", "app1");
        es.setCode("elasticsearch");
        when(middleware.get("mwi_1", "t1")).thenReturn(es);
        ConnInfo info = new ConnInfo();
        info.setHost("es.demo.svc");
        info.setPort(9200);
        info.setSecretName("es-user");
        info.setPasswordKey("elastic");
        info.setExtra(scheme == null ? Map.of() : Map.of("scheme", scheme));
        MiddlewareConnectionDTO dto = new MiddlewareConnectionDTO();
        dto.setInfo(info);
        when(middleware.connection("mwi_1", "t1")).thenReturn(dto);
        when(client.init(any())).thenReturn(new com.coding.common.models.app.AppInitResultDTO());
    }

    Map<String, String> esEnv(String scheme) {
        readyElasticsearch(scheme);
        AppDependencyRequest r = initReq(null);
        r.setInitPath("/orders");
        service.init(r, "alice", "t1");
        ArgumentCaptor<com.coding.common.models.app.AppInitRequestDTO> dto =
                ArgumentCaptor.forClass(com.coding.common.models.app.AppInitRequestDTO.class);
        verify(client).init(dto.capture());
        return dto.getValue().getEnv();
    }

    @Test
    void elasticsearchInitUsesTheInstancesSchemeAndTheRequestedPath() {
        assertThat(esEnv("https")).containsEntry("URL", "https://es.demo.svc:9200")
                .containsEntry("INDEX_PATH", "/orders").doesNotContainKey("PATH");
    }

    @Test
    void elasticsearchInitUsesHttpWhenTlsIsOff() {
        assertThat(esEnv("http")).containsEntry("URL", "http://es.demo.svc:9200");
    }

    @Test
    void elasticsearchInitRequiresAValidPath() {
        readyElasticsearch("https");
        for (String bad : new String[]{null, "", "orders", "/a b", "/x';rm"}) {
            AppDependencyRequest r = initReq(null);
            r.setInitPath(bad);
            assertThatThrownBy(() -> service.init(r, "alice", "t1")).as(String.valueOf(bad))
                    .isInstanceOf(CloudPlatformException.class);
        }
        verify(client, never()).init(any());
    }

    @Test
    void nonElasticsearchEnvHasNoUrlOrIndexPath() {
        readyMysqlDependency(null, null);
        when(client.init(any())).thenReturn(new com.coding.common.models.app.AppInitResultDTO());
        service.init(initReq(null), "alice", "t1");
        ArgumentCaptor<com.coding.common.models.app.AppInitRequestDTO> dto =
                ArgumentCaptor.forClass(com.coding.common.models.app.AppInitRequestDTO.class);
        verify(client).init(dto.capture());
        assertThat(dto.getValue().getEnv()).containsOnlyKeys("HOST", "PORT");
    }

    // ---- 并发上限与关闭

    @Test
    void rejectedSubmissionMarksFailedAndTellsUserToRetryLater() {
        readyMysqlDependency(null, null);
        AppDependencyService busy = new AppDependencyService(apps, services, deps, releases, executions, instances,
                refs, middleware, client, r -> {
                    throw new java.util.concurrent.RejectedExecutionException("full");
                });

        assertThatThrownBy(() -> busy.init(initReq(null), "alice", "t1")).hasMessageContaining("过多");
        verify(deps).finishInit(org.mockito.ArgumentMatchers.eq(5L), org.mockito.ArgumentMatchers.eq("FAILED"),
                org.mockito.ArgumentMatchers.isNull(), anyString(), any());
    }

    @Test
    void ownedPoolIsShutDownOnClose() {
        java.util.concurrent.ExecutorService pool = mock(java.util.concurrent.ExecutorService.class);
        new AppDependencyService(apps, services, deps, releases, executions, instances, refs, middleware, client,
                pool).shutdown();
        verify(pool).shutdownNow();
    }

    // ---- 旧模型应用只读 ----

    @Test
    void legacyAppRejectsDependencyAddRemoveInit() {
        app.setSpecJson("{\"nodes\":[]}");
        assertThatThrownBy(() -> service.add(req("db"), "bob", "t1")).hasMessage("旧模型应用只能删除，请删除后按新方式重建");
        assertThatThrownBy(() -> service.remove(req("db"), "bob", "t1")).hasMessage("旧模型应用只能删除，请删除后按新方式重建");
        assertThatThrownBy(() -> service.init(req("db"), "bob", "t1")).hasMessage("旧模型应用只能删除，请删除后按新方式重建");
        verifyNoInteractions(deps, instances, refs, middleware, client);
    }
}
