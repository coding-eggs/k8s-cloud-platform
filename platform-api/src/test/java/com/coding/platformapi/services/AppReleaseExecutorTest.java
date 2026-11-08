package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.app.AppApplyRequestDTO;
import com.coding.common.models.app.AppObjectRefDTO;
import com.coding.common.models.app.AppObjectStatusDTO;
import com.coding.common.models.app.AppPruneRequestDTO;
import com.coding.common.models.app.AppRenderRequestDTO;
import com.coding.common.models.app.AppRenderResultDTO;
import com.coding.common.models.app.AppStatusResultDTO;
import com.coding.data.mapper.system.PlatformAppExecutionLogMapper;
import com.coding.data.mapper.system.PlatformAppExecutionMapper;
import com.coding.data.mapper.system.PlatformAppReleaseMapper;
import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.mapper.system.PlatformApplicationResourceMapper;
import com.coding.data.models.system.PlatformAppExecution;
import com.coding.data.models.system.PlatformAppRelease;
import com.coding.data.models.system.PlatformApplication;
import com.coding.data.models.system.PlatformApplicationResource;
import com.coding.platformapi.k8s.K8sAppClient;
import com.coding.platformapi.k8s.K8sServerGateway;
import com.coding.platformapi.k8s.K8sServerUnauthorizedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppReleaseExecutorTest {

    private static final String OBJECTS = """
            [{"apiVersion":"apps/v1","kind":"Deployment","namespace":"demo","name":"demo-web"}]
            """;

    private PlatformApplicationMapper apps;
    private PlatformAppReleaseMapper releases;
    private PlatformAppExecutionMapper executions;
    private PlatformApplicationResourceMapper resources;
    private K8sAppClient client;
    private ApplicationDeployCredentials credentials;
    private AppExecutionLease leases;
    private AppReleasePrecheck precheck;
    private AppReleaseRenderer renderer;
    private AppReleaseExecutor executor;
    private PlatformAppExecutionLogMapper logMapper;
    private PlatformApplication app;
    private PlatformAppRelease release;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        apps = mock(PlatformApplicationMapper.class);
        releases = mock(PlatformAppReleaseMapper.class);
        executions = mock(PlatformAppExecutionMapper.class);
        resources = mock(PlatformApplicationResourceMapper.class);
        client = mock(K8sAppClient.class);
        credentials = new ApplicationDeployCredentials();
        leases = new AppExecutionLease("test-instance");
        precheck = mock(AppReleasePrecheck.class);
        renderer = mock(AppReleaseRenderer.class);
        ObjectProvider<AppReleasePrecheck> precheckProvider = mock(ObjectProvider.class);
        ObjectProvider<AppReleaseRenderer> rendererProvider = mock(ObjectProvider.class);
        when(precheckProvider.getIfAvailable()).thenReturn(precheck);
        when(rendererProvider.getIfAvailable()).thenReturn(renderer);
        logMapper = mock(PlatformAppExecutionLogMapper.class);
        executor = new AppReleaseExecutor(apps, releases, executions, resources, client, credentials, leases,
                precheckProvider, rendererProvider, new AppExecutionJournal(logMapper), 30);

        app = new PlatformApplication();
        app.setId("app_1");
        app.setClusterId("c1");
        app.setNamespace("demo");
        app.setSlug("demo");
        app.setLiveReleaseNo(0);
        when(apps.selectById("app_1")).thenReturn(app);

        release = new PlatformAppRelease();
        release.setId(50L);
        release.setAppId("app_1");
        release.setReleaseNo(3);
        release.setObjectsJson(OBJECTS);
        release.setManifest("kind: Deployment");
        release.setStatus("RENDERED");
        when(releases.selectByNo("app_1", 3)).thenReturn(release);
        when(releases.selectMaxNo("app_1")).thenReturn(3);
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private PlatformAppExecution at(String stage) {
        PlatformAppExecution e = new PlatformAppExecution();
        e.setId(7L);
        e.setAppId("app_1");
        e.setReleaseNo(3);
        e.setStatus("RUNNING");
        e.setStage(stage);
        e.setStartedAt(new Date());
        credentials.rememberExecution(7L, "Bearer user");
        return e;
    }

    private static AppStatusResultDTO statusOf(String... states) {
        AppStatusResultDTO r = new AppStatusResultDTO();
        r.setObjects(Arrays.stream(states).map(s -> {
            AppObjectStatusDTO o = new AppObjectStatusDTO();
            o.setKind("Deployment");
            o.setName("demo-web");
            o.setState(s);
            return o;
        }).toList());
        return r;
    }

    private PlatformAppExecution lastSaved() {
        ArgumentCaptor<PlatformAppExecution> c = ArgumentCaptor.forClass(PlatformAppExecution.class);
        verify(executions, atLeastOnce()).updateById(c.capture());
        return c.getValue();
    }

    private static PlatformApplicationResource ledger(String kind, String name) {
        PlatformApplicationResource r = new PlatformApplicationResource();
        r.setApiVersion("v1");
        r.setKind(kind);
        r.setNamespace("demo");
        r.setName(name);
        return r;
    }

    @Test
    void oneAdvanceMovesExactlyOneStageAndPersistsIt() {
        PlatformAppExecution e = at("PRECHECK");

        executor.advance(e);

        verify(precheck).check(e);
        assertThat(lastSaved().getStage()).isEqualTo("RENDER");
        assertThat(lastSaved().getStatus()).isEqualTo("RUNNING");
        verify(client, never()).render(any());
        verify(client, never()).status(any());
    }

    @Test
    void savesNeverWriteTheStaleLeaseBack() {
        PlatformAppExecution e = at("PRECHECK");
        e.setLeaseUntil(new Date());

        executor.advance(e);

        assertThat(lastSaved().getLeaseUntil()).isNull();
    }

    @Test
    void renderStoresManifestAndObjectsThroughTheDedicatedMapperMethod() {
        release.setManifest(null);
        release.setObjectsJson(null);
        AppRenderRequestDTO req = new AppRenderRequestDTO();
        when(renderer.build(app, release)).thenReturn(req);
        AppRenderResultDTO rendered = new AppRenderResultDTO();
        rendered.setManifest("kind: Deployment");
        AppObjectRefDTO ref = new AppObjectRefDTO();
        ref.setKind("Deployment");
        ref.setName("demo-web");
        rendered.setObjects(List.of(ref));
        when(client.render(req)).thenReturn(rendered);
        when(releases.updateRendered(eq(50L), any(), any())).thenReturn(1);

        executor.advance(at("RENDER"));

        // PlatformAppReleaseMapper.updateById 只写 status / note，走它清单就丢了
        ArgumentCaptor<String> objects = ArgumentCaptor.forClass(String.class);
        verify(releases).updateRendered(eq(50L), eq("kind: Deployment"), objects.capture());
        assertThat(objects.getValue()).contains("demo-web");
        assertThat(lastSaved().getStage()).isEqualTo("CONFLICT");
    }

    @Test
    void renderIsSkippedWhenManifestAlreadyExists() {
        executor.advance(at("RENDER"));

        verify(client, never()).render(any());
        assertThat(lastSaved().getStage()).isEqualTo("CONFLICT");
    }

    @Test
    void conflictFailsOnForeignObject() {
        when(client.status(any())).thenReturn(statusOf("READY"));
        when(resources.selectByApp("app_1")).thenReturn(List.of());

        executor.advance(at("CONFLICT"));

        assertThat(lastSaved().getStatus()).isEqualTo("FAILED");
        assertThat(lastSaved().getMessage()).contains("Deployment/demo-web");
    }

    @Test
    void applyWritesLedgerUnderReleaseNumberBeforeTouchingTheCluster() {
        executor.advance(at("APPLY"));

        InOrder order = inOrder(resources, client);
        order.verify(resources).deleteByAppRevision("app_1", 3);
        order.verify(resources).batchInsert(any());
        order.verify(client).apply(any(AppApplyRequestDTO.class));
        assertThat(lastSaved().getStage()).isEqualTo("HEALTH");
    }

    @Test
    void healthNotReadyKeepsStageAndDoesNotPruneOrFail() {
        when(client.status(any())).thenReturn(statusOf("PENDING"));

        PlatformAppExecution e = at("HEALTH");
        executor.advance(e);

        assertThat(e.getStage()).isEqualTo("HEALTH");
        assertThat(e.getStatus()).isEqualTo("RUNNING");
        verify(client, never()).prune(any());
    }

    @Test
    void healthWaitingMessageNamesNotReadyObjects() {
        AppStatusResultDTO s = statusOf("PENDING");
        s.getObjects().get(0).setMessage("0/2 副本就绪");
        when(client.status(any())).thenReturn(s);

        PlatformAppExecution e = at("HEALTH");
        executor.advance(e);

        assertThat(e.getMessage()).contains("Deployment/demo-web").contains("PENDING").contains("0/2 副本就绪");
        assertThat(lastSaved().getMessage()).contains("Deployment/demo-web");
    }

    @Test
    void healthJournalListsPodIssuesAndSkipsUnchangedTicks() {
        AppStatusResultDTO s = statusOf("PENDING");
        s.getObjects().get(0).setMessage("0/1 副本就绪");
        s.getObjects().get(0).setPodIssues(List.of("Pod demo-web-abc：容器 web 拉取镜像失败（ImagePullBackOff）"));
        when(client.status(any())).thenReturn(s);

        PlatformAppExecution e = at("HEALTH");
        executor.advance(e);

        org.mockito.ArgumentCaptor<com.coding.data.models.system.PlatformAppExecutionLog> row =
                org.mockito.ArgumentCaptor.forClass(com.coding.data.models.system.PlatformAppExecutionLog.class);
        verify(logMapper).insert(row.capture());
        assertThat(row.getValue().getStage()).isEqualTo("HEALTH");
        assertThat(row.getValue().getMessage()).contains("0/1 已就绪").contains("Deployment/demo-web 未就绪")
                .contains("  · Pod demo-web-abc：容器 web 拉取镜像失败");
        assertThat(e.getMessage()).contains("ImagePullBackOff");

        // 下一拍状态没变：不再追加
        when(logMapper.selectLastByExecution(7L)).thenReturn(row.getValue());
        executor.advance(at("HEALTH"));
        verify(logMapper, org.mockito.Mockito.times(1)).insert(any());
    }

    @Test
    void journalFailureDoesNotFailTheRelease() {
        when(logMapper.insert(any())).thenThrow(new RuntimeException("表不存在"));
        when(client.status(any())).thenReturn(statusOf("READY"));

        executor.advance(at("HEALTH"));

        assertThat(lastSaved().getStage()).isEqualTo("PRUNE");
        assertThat(lastSaved().getStatus()).isEqualTo("RUNNING");
    }

    @Test
    void healthTimeoutMessageNamesNotReadyObjectsAndStaysWithinColumn() {
        AppStatusResultDTO s = statusOf("FAILED");
        s.getObjects().get(0).setMessage("x".repeat(3000));
        when(client.status(any())).thenReturn(s);
        PlatformAppExecution e = at("HEALTH");
        e.setStartedAt(Date.from(Instant.now().minus(Duration.ofMinutes(31))));

        executor.advance(e);

        assertThat(lastSaved().getStatus()).isEqualTo("FAILED");
        assertThat(lastSaved().getMessage()).contains("Deployment/demo-web").contains("FAILED");
        assertThat(lastSaved().getMessage().length()).isLessThanOrEqualTo(1024);
    }

    @Test
    void healthWithoutAnswerForEveryObjectIsNotReady() {
        when(client.status(any())).thenReturn(new AppStatusResultDTO());

        PlatformAppExecution e = at("HEALTH");
        executor.advance(e);

        assertThat(e.getStage()).isEqualTo("HEALTH");
    }

    @Test
    void healthReadyMovesToPruneWithoutPruning() {
        when(client.status(any())).thenReturn(statusOf("READY"));

        executor.advance(at("HEALTH"));

        assertThat(lastSaved().getStage()).isEqualTo("PRUNE");
        verify(client, never()).prune(any());
    }

    @Test
    void healthTimeoutFails() {
        when(client.status(any())).thenReturn(statusOf("PENDING"));
        PlatformAppExecution e = at("HEALTH");
        e.setStartedAt(Date.from(Instant.now().minus(Duration.ofMinutes(31))));

        executor.advance(e);

        assertThat(lastSaved().getStatus()).isEqualTo("FAILED");
        assertThat(lastSaved().getMessage()).contains("30");
    }

    @Test
    void pruneBaselineIsEveryLedgerRowFromLiveUpToButExcludingTheCurrentReleaseAndKeepsPvc() {
        app.setLiveReleaseNo(2);
        when(resources.selectByRevisionRange("app_1", 2, 3)).thenReturn(List.of(
                ledger("Deployment", "demo-web"), ledger("Deployment", "demo-old")));

        executor.advance(at("PRUNE"));

        ArgumentCaptor<AppPruneRequestDTO> req = ArgumentCaptor.forClass(AppPruneRequestDTO.class);
        verify(client).prune(req.capture());
        assertThat(req.getValue().getObjects()).extracting(AppObjectRefDTO::getName).containsExactly("demo-old");
        assertThat(req.getValue().isDeletePvc()).isFalse();
        assertThat(lastSaved().getStage()).isEqualTo("DONE");
    }

    @Test
    void objectsFromAFailedReleaseArePrunedByTheNextHealthyRelease() {
        // live=1，发布 2 失败（留下 demo-half），发布 3 健康：基线必须覆盖 [1,3)
        app.setLiveReleaseNo(1);
        when(resources.selectByRevisionRange("app_1", 1, 3)).thenReturn(List.of(
                ledger("Deployment", "demo-web"), ledger("Deployment", "demo-half")));

        executor.advance(at("PRUNE"));

        ArgumentCaptor<AppPruneRequestDTO> req = ArgumentCaptor.forClass(AppPruneRequestDTO.class);
        verify(client).prune(req.capture());
        assertThat(req.getValue().getObjects()).extracting(AppObjectRefDTO::getName).containsExactly("demo-half");
    }

    @Test
    void pruneWithNothingLiveStillCoversEarlierFailedAttempts() {
        when(resources.selectByRevisionRange("app_1", 0, 3)).thenReturn(List.of(
                ledger("Deployment", "demo-half")));

        executor.advance(at("PRUNE"));

        ArgumentCaptor<AppPruneRequestDTO> req = ArgumentCaptor.forClass(AppPruneRequestDTO.class);
        verify(client).prune(req.capture());
        assertThat(req.getValue().getObjects()).extracting(AppObjectRefDTO::getName).containsExactly("demo-half");
    }

    // ------------------------------------------------------------------ 下线

    private void asOffline() {
        release.setType(AppReleaseExecutor.OFFLINE_TYPE);
        release.setManifest(null);
        release.setObjectsJson(null);
        app.setLiveReleaseNo(2);
    }

    @Test
    void offlineSkipsPrecheckAndGoesStraightToPrune() {
        asOffline();
        PlatformAppExecution e = at("PRECHECK");

        executor.advance(e);

        verify(precheck, never()).check(any());
        verify(client, never()).render(any());
        assertThat(lastSaved().getStage()).isEqualTo("PRUNE");
    }

    /** 续跑停在中间阶段的下线（理论上到不了）：也不能去渲染 / 下发 */
    @Test
    void offlineNeverRendersOrAppliesEvenIfResumedMidway() {
        asOffline();
        for (String stage : List.of("RENDER", "CONFLICT", "APPLY", "HEALTH")) {
            PlatformAppExecution e = at(stage);
            executor.advance(e);
            assertThat(e.getStage()).as(stage).isEqualTo("PRUNE");
        }
        verify(client, never()).render(any());
        verify(client, never()).apply(any());
        verify(client, never()).status(any());
    }

    /** 下线删台账里的全部对象（不分发布号、去重），PVC 保留；空清单守卫不挡它 */
    @Test
    void offlinePrunesEveryOwnedObjectOnceAndKeepsPvc() {
        asOffline();
        when(resources.selectByApp("app_1")).thenReturn(List.of(
                ledger("Deployment", "demo-web"), ledger("Service", "demo-web"),
                ledger("Deployment", "demo-web"), ledger("Deployment", "demo-half")));

        executor.advance(at("PRUNE"));

        ArgumentCaptor<AppPruneRequestDTO> req = ArgumentCaptor.forClass(AppPruneRequestDTO.class);
        verify(client).prune(req.capture());
        assertThat(req.getValue().getObjects()).extracting(o -> o.getKind() + "/" + o.getName())
                .containsExactly("Deployment/demo-web", "Service/demo-web", "Deployment/demo-half");
        assertThat(req.getValue().isDeletePvc()).isFalse();
        verify(resources, never()).selectByRevisionRange(any(), anyInt(), anyInt());
        assertThat(lastSaved().getStage()).isEqualTo("DONE");
    }

    @Test
    void offlineFinishPointsLiveAtTheOfflineReleaseAndMarksAppOffline() {
        asOffline();

        executor.advance(at("DONE"));

        ArgumentCaptor<PlatformApplication> patch = ArgumentCaptor.forClass(PlatformApplication.class);
        verify(apps).updateById(patch.capture());
        assertThat(patch.getValue().getStatus()).isEqualTo("OFFLINE");
        assertThat(patch.getValue().getLiveReleaseNo()).isEqualTo(3);
    }

    @Test
    void normalFinishStillMarksAppReady() {
        executor.advance(at("DONE"));

        ArgumentCaptor<PlatformApplication> patch = ArgumentCaptor.forClass(PlatformApplication.class);
        verify(apps).updateById(patch.capture());
        assertThat(patch.getValue().getStatus()).isEqualTo("READY");
    }

    @Test
    void pruneSkipsWhenThereIsNothingStale() {
        executor.advance(at("PRUNE"));

        verify(client, never()).prune(any());
        assertThat(lastSaved().getStage()).isEqualTo("DONE");
    }

    @Test
    void transientStatusFailureDuringHealthKeepsWaiting() {
        when(client.status(any())).thenThrow(new CloudPlatformException(EnumResponseType.ERROR, "502 bad gateway"));

        PlatformAppExecution e = at("HEALTH");
        executor.advance(e);

        assertThat(e.getStage()).isEqualTo("HEALTH");
        assertThat(e.getStatus()).isEqualTo("RUNNING");
        verify(client, never()).prune(any());
    }

    @Test
    void persistentStatusFailureDuringHealthStillEndsAtTheTimeout() {
        when(client.status(any())).thenThrow(new CloudPlatformException(EnumResponseType.ERROR, "502 bad gateway"));
        PlatformAppExecution e = at("HEALTH");
        e.setStartedAt(Date.from(Instant.now().minus(Duration.ofMinutes(31))));

        executor.advance(e);

        assertThat(lastSaved().getStatus()).isEqualTo("FAILED");
        assertThat(lastSaved().getMessage()).contains("30");
    }

    @Test
    void status401DuringHealthStillInterrupts() {
        when(client.status(any())).thenThrow(new K8sServerUnauthorizedException(401, "401"));

        PlatformAppExecution e = at("HEALTH");
        executor.advance(e);

        assertThat(lastSaved().getStatus()).isEqualTo("INTERRUPTED");
    }

    @Test
    void emptyObjectListNeverPrunes() {
        app.setLiveReleaseNo(2);
        release.setObjectsJson("[]");

        executor.advance(at("PRUNE"));

        verify(client, never()).prune(any());
        assertThat(lastSaved().getStatus()).isEqualTo("FAILED");
    }

    @Test
    void emptyObjectListFailsBeforeHealth() {
        release.setObjectsJson(null);

        executor.advance(at("HEALTH"));

        verify(client, never()).status(any());
        assertThat(lastSaved().getStatus()).isEqualTo("FAILED");
    }

    @Test
    void renderFailsWhenNoRowWasUpdatedAndStillNoManifest() {
        release.setManifest("");
        AppRenderRequestDTO req = new AppRenderRequestDTO();
        when(renderer.build(app, release)).thenReturn(req);
        AppRenderResultDTO rendered = new AppRenderResultDTO();
        rendered.setManifest("kind: Deployment");
        when(client.render(req)).thenReturn(rendered);
        when(releases.updateRendered(eq(50L), any(), any())).thenReturn(0);

        executor.advance(at("RENDER"));

        assertThat(lastSaved().getStatus()).isEqualTo("FAILED");
    }

    @Test
    void doneMovesLiveMarksDeployedAndFinishesLast() {
        executor.advance(at("DONE"));

        ArgumentCaptor<PlatformApplication> a = ArgumentCaptor.forClass(PlatformApplication.class);
        verify(apps).updateById(a.capture());
        assertThat(a.getValue().getLiveReleaseNo()).isEqualTo(3);
        assertThat(a.getValue().getStatus()).isEqualTo("READY");
        ArgumentCaptor<PlatformAppRelease> r = ArgumentCaptor.forClass(PlatformAppRelease.class);
        verify(releases).updateById(r.capture());
        assertThat(r.getValue().getStatus()).isEqualTo("DEPLOYED");
        assertThat(lastSaved().getStatus()).isEqualTo("SUCCESS");
        assertThat(lastSaved().getFinishedAt()).isNotNull();
        assertThat(credentials.getExecution(7L)).isNull();

        InOrder order = inOrder(apps, executions);
        order.verify(apps).updateById(any());
        order.verify(executions).updateById(any());
    }

    /** 模拟重启：凭据只在内存里，重启就没了。 */
    @Test
    void missingCredentialsInterruptAndKeepStage() {
        PlatformAppExecution e = at("HEALTH");
        credentials.forgetExecution(7L);

        executor.advance(e);

        assertThat(lastSaved().getStatus()).isEqualTo("INTERRUPTED");
        assertThat(lastSaved().getStage()).isEqualTo("HEALTH");
        // 两种中断原因必须能分清：这一条是「本实例没有凭据」，不是 k8s-server 拒绝
        assertThat(lastSaved().getMessage()).contains("凭据已失效").doesNotContain("401");
        verify(client, never()).status(any());
        verify(releases, never()).updateById(any());
    }

    @Test
    void gateway401Interrupts() {
        doThrow(new K8sServerUnauthorizedException(4003, "未登录")).when(client).apply(any());
        PlatformAppExecution e = at("APPLY");

        executor.advance(e);

        assertThat(lastSaved().getStatus()).isEqualTo("INTERRUPTED");
        assertThat(lastSaved().getStage()).isEqualTo("APPLY");
        assertThat(lastSaved().getMessage()).contains("401");
        assertThat(credentials.getExecution(7L)).isNull();
    }

    @Test
    void wrapped401InPrecheckInterrupts() {
        PlatformAppExecution e = at("PRECHECK");
        doThrow(new IllegalStateException("x", new K8sServerUnauthorizedException(4003, "未登录")))
                .when(precheck).check(e);

        executor.advance(e);

        assertThat(lastSaved().getStatus()).isEqualTo("INTERRUPTED");
    }

    /** 4004 同时是「session 过期」和「kube config 不存在」，业务码不能当 401 判据。 */
    @Test
    void businessCodeThatLooksLikeAuthStillFails() {
        doThrow(new CloudPlatformException(EnumResponseType.NON_KUBE_CONFIG)).when(client).apply(any());

        executor.advance(at("APPLY"));

        assertThat(lastSaved().getStatus()).isEqualTo("FAILED");
    }

    @Test
    void otherExceptionsFailWithMessageAndBoundedLength() {
        doThrow(new CloudPlatformException(EnumResponseType.ERROR, "x".repeat(3000))).when(client).apply(any());

        executor.advance(at("APPLY"));

        assertThat(lastSaved().getStatus()).isEqualTo("FAILED");
        assertThat(lastSaved().getMessage()).hasSize(1024);
        ArgumentCaptor<PlatformAppRelease> r = ArgumentCaptor.forClass(PlatformAppRelease.class);
        verify(releases).updateById(r.capture());
        assertThat(r.getValue().getStatus()).isEqualTo("FAILED");
    }

    @Test
    void callsRunUnderTheStoredAuthorization() {
        AtomicReference<String> seen = new AtomicReference<>();
        doAnswer(inv -> {
            seen.set(K8sServerGateway.delegatedAuthorization());
            return null;
        }).when(client).apply(any());

        executor.advance(at("APPLY"));

        assertThat(seen.get()).isEqualTo("Bearer user");
    }

    @Test
    void withoutImplementationsTheExecutionFailsClearly() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AppReleasePrecheck> none = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AppReleaseRenderer> noneR = mock(ObjectProvider.class);
        AppReleaseExecutor bare = new AppReleaseExecutor(apps, releases, executions, resources, client,
                credentials, leases, none, noneR, new AppExecutionJournal(logMapper), 30);

        bare.advance(at("PRECHECK"));

        assertThat(lastSaved().getStatus()).isEqualTo("FAILED");
    }

    // ---------- resume ----------

    private PlatformAppExecution stored(String status, String stage) {
        PlatformAppExecution e = new PlatformAppExecution();
        e.setId(7L);
        e.setAppId("app_1");
        e.setReleaseNo(3);
        e.setStatus(status);
        e.setStage(stage);
        e.setStartedAt(new Date());
        when(executions.selectById(7L)).thenReturn(e);
        when(executions.selectLatestByApp("app_1")).thenReturn(e);
        when(executions.reopen(eq(7L), eq("PENDING"), any(), any(), any())).thenReturn(1);
        return e;
    }

    private static void requestWithToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", token);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void resumeReopensKeepsStageAndReleaseAndCapturesCurrentToken() {
        stored("INTERRUPTED", "HEALTH");
        requestWithToken("Bearer fresh");

        executor.resume(7L, "alice");

        verify(executions).reopen(eq(7L), eq("PENDING"), any(), any(), any());
        verify(executions, never()).updateById(any());
        assertThat(credentials.getExecution(7L)).isEqualTo("Bearer fresh");
    }

    /** 否则中断一小时后点「继续」，HEALTH 会立刻判超时。 */
    @Test
    void resumeRestartsTheTimeoutClock() {
        stored("FAILED", "HEALTH");
        requestWithToken("Bearer fresh");
        Date before = new Date();

        executor.resume(7L, "alice");

        ArgumentCaptor<Date> clock = ArgumentCaptor.forClass(Date.class);
        verify(executions).reopen(eq(7L), eq("PENDING"), clock.capture(), any(), any());
        assertThat(clock.getValue()).isAfterOrEqualTo(before);
    }

    @Test
    void resumeOfFailedPutsReleaseBackToRendered() {
        stored("FAILED", "APPLY");
        release.setStatus("FAILED");
        requestWithToken("Bearer fresh");

        executor.resume(7L, "alice");

        ArgumentCaptor<PlatformAppRelease> r = ArgumentCaptor.forClass(PlatformAppRelease.class);
        verify(releases).updateById(r.capture());
        assertThat(r.getValue().getStatus()).isEqualTo("RENDERED");
        ArgumentCaptor<PlatformApplication> a = ArgumentCaptor.forClass(PlatformApplication.class);
        verify(apps).updateById(a.capture());
        assertThat(a.getValue().getStatus()).isEqualTo("DEPLOYING");
    }

    @Test
    void resumedExecutionContinuesFromItsStageWithSameReleaseNumber() {
        PlatformAppExecution e = stored("INTERRUPTED", "APPLY");
        requestWithToken("Bearer fresh");
        executor.resume(7L, "alice");
        e.setStatus("PENDING");

        executor.advance(e);

        verify(client).apply(any());
        verify(releases, never()).insert(any());
        assertThat(lastSaved().getStage()).isEqualTo("HEALTH");
        assertThat(lastSaved().getStatus()).isEqualTo("RUNNING");
        assertThat(e.getReleaseNo()).isEqualTo(3);
    }

    @Test
    void resumeRejectedWhenNotFailedOrInterrupted() {
        stored("RUNNING", "APPLY");
        requestWithToken("Bearer fresh");

        assertThatThrownBy(() -> executor.resume(7L, "alice")).isInstanceOf(CloudPlatformException.class);
        verify(executions, never()).reopen(anyLong(), anyString(), any(), any(), any());
    }

    @Test
    void resumeRejectedWhenNewerExecutionExists() {
        stored("FAILED", "APPLY");
        PlatformAppExecution newer = new PlatformAppExecution();
        newer.setId(8L);
        when(executions.selectLatestByApp("app_1")).thenReturn(newer);
        requestWithToken("Bearer fresh");

        assertThatThrownBy(() -> executor.resume(7L, "alice")).isInstanceOf(CloudPlatformException.class);
        verify(executions, never()).reopen(anyLong(), anyString(), any(), any(), any());
    }

    @Test
    void resumeRejectedWhenNewerReleaseExists() {
        stored("FAILED", "APPLY");
        when(releases.selectMaxNo("app_1")).thenReturn(4);
        requestWithToken("Bearer fresh");

        assertThatThrownBy(() -> executor.resume(7L, "alice")).isInstanceOf(CloudPlatformException.class);
        verify(executions, never()).reopen(anyLong(), anyString(), any(), any(), any());
    }

    @Test
    void resumeRejectedWithoutACurrentToken() {
        stored("INTERRUPTED", "APPLY");

        assertThatThrownBy(() -> executor.resume(7L, "alice")).isInstanceOf(CloudPlatformException.class);
        verify(executions, never()).reopen(anyLong(), anyString(), any(), any(), any());
    }

    @Test
    void resumeLosingTheRaceIsRejectedAndLeavesNoToken() {
        stored("INTERRUPTED", "APPLY");
        when(executions.reopen(eq(7L), eq("PENDING"), any(), any(), any())).thenReturn(0);
        requestWithToken("Bearer fresh");

        assertThatThrownBy(() -> executor.resume(7L, "alice")).isInstanceOf(CloudPlatformException.class);
        assertThat(credentials.getExecution(7L)).isNull();
    }

    @Test
    void resumeRacingWithSupersedeSurfacesBusinessErrorAndLeavesNoToken() {
        stored("FAILED", "APPLY");
        when(executions.reopen(eq(7L), eq("PENDING"), any(), any(), any()))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_app_exec_active"));
        requestWithToken("Bearer fresh");

        assertThatThrownBy(() -> executor.resume(7L, "alice"))
                .isInstanceOf(CloudPlatformException.class).hasMessageContaining("新的发布");
        assertThat(credentials.getExecution(7L)).isNull();
        verify(releases, never()).updateById(any());
        verify(apps, never()).updateById(any());
    }

    @Test
    void offlineFinishReleasesGatewayPorts() {
        asOffline();

        executor.advance(at("DONE"));

        verify(precheck).afterOffline(any());
    }

    @Test
    void normalFinishDoesNotReleasePorts() {
        executor.advance(at("DONE"));

        verify(precheck, never()).afterOffline(any());
    }
}
