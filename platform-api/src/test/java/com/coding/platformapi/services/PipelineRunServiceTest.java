package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.PipelineDTO;
import com.coding.common.models.k8s.dto.PipelineRunDTO;
import com.coding.common.models.pipeline.PipelineRunLogDTO;
import com.coding.data.mapper.system.PlatformPipelineRunLogMapper;
import com.coding.data.mapper.system.PlatformPipelineRunMapper;
import com.coding.data.models.system.PlatformPipelineRun;
import com.coding.data.models.system.PlatformPipelineRunLog;
import com.coding.platformapi.k8s.K8sPipelineClient;
import com.coding.platformapi.models.PipelineRunContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineRunServiceTest {

    private final PlatformPipelineRunMapper runMapper = mock(PlatformPipelineRunMapper.class);
    private final K8sPipelineClient k8s = mock(K8sPipelineClient.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final NamespaceAccessGuard access = mock(NamespaceAccessGuard.class);

    private final PlatformPipelineRunLogMapper logMapper = mock(PlatformPipelineRunLogMapper.class);

    private final com.coding.platformapi.security.AuthContext auth =
            mock(com.coding.platformapi.security.AuthContext.class);

    private final PipelineRunService svc =
            new PipelineRunService(runMapper, logMapper, k8s, credentials, access, auth);

    private final PipelineRunContext ctx = new PipelineRunContext("c1", "ns1", "t1");

    private static PipelineDTO pipeline(String canvas) {
        PipelineDTO p = new PipelineDTO();
        p.setId("p1");
        p.setClusterId("c1");
        p.setNamespace("ns1");
        p.setName("my-app");
        p.setCanvasJson(canvas);
        return p;
    }

    private static String canvas(String... connectionIds) {
        StringBuilder nodes = new StringBuilder();
        for (int i = 0; i < connectionIds.length; i++) {
            if (i > 0) {
                nodes.append(',');
            }
            nodes.append("{\"id\":\"n").append(i).append("\",\"type\":\"git\",\"config\":{")
                 .append("\"connectionId\":\"").append(connectionIds[i]).append("\"}}");
        }
        return "{\"nodes\":[" + nodes + "],\"edges\":[]}";
    }

    // ---- 触发 ----

    @Test
    void theRunNameIsGeneratedAndPersistedBeforeTheClusterCall() {
        // ⭐ spec §4.5①：run_name 由平台先生成 → 幂等键从源头就有，
        //    也不会出现「集群里有对象、库里没记录」的孤儿
        when(k8s.createRun(anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(new PipelineRunDTO());

        String runName = svc.run(pipeline(canvas("conn-1")), "alice", ctx);

        InOrder inOrder = inOrder(runMapper, k8s);
        inOrder.verify(runMapper).insert(any());                                  // 先落库
        inOrder.verify(k8s).createRun(anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), anyBoolean());
        assertThat(runName).isNotBlank().startsWith("my-app-");
    }

    @Test
    void aFailedClusterCreateDoesNotLeaveARunningOrphan() {
        // ⭐ 先落库再创建的代价要在这里堵住
        when(k8s.createRun(anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), anyBoolean()))
                .thenThrow(new CloudPlatformException(EnumResponseType.ERROR, "boom"));

        assertThatThrownBy(() -> svc.run(pipeline(canvas("conn-1")), "alice", ctx))
                .isInstanceOf(CloudPlatformException.class);

        verify(runMapper).deleteById(any());        // 库里不能留一条 RUNNING 的孤儿
    }

    @Test
    void everyConnectionTheCanvasReferencesMustBeAuthorized() {
        // ⭐ Review Focus #4：绕过前端直接打接口，引用一条没被授权的连接也要被拒
        when(auth.hatTenantId()).thenReturn("t1");   // 租户自己跑：按他自己的租户判
        svc.run(pipeline(canvas("conn-1")), "alice", ctx);

        verify(credentials).resolveForPipeline("conn-1", "GIT", "t1");
    }

    /**
     * 平台管理员（无租户帽）跑平台命名空间里的流水线：请求里带的「生效租户」是 t1
     * （前端顶栏选的），但**连接可见性要按调用者判**。
     *
     * <p>2026-10-29 实报：`Harbor platform@<集群>` 这种平台共享凭据在下拉与预览里都出现
     * （那两处用的就是 hat=null），运行期却按目标租户判 → 「该连接未授权在流水线里使用」。
     * 三处口径必须一致，否则用户配得出来、跑不起来。
     */
    @Test
    void connectionVisibilityFollowsTheCallerNotTheTargetTenant() {
        when(auth.hatTenantId()).thenReturn(null);   // 平台管理员

        svc.run(pipeline(canvas("harbor-platform")), "admin", ctx);   // ctx 的租户是 t1

        ArgumentCaptor<String> tenantId = ArgumentCaptor.forClass(String.class);
        verify(credentials).resolveForPipeline(eq("harbor-platform"), any(), tenantId.capture());
        assertThat(tenantId.getValue()).as("按调用者（无租户帽）判，而不是 ctx 里的 t1").isNull();
    }

    @Test
    void anUnauthorizedConnectionStopsTheRunBeforeAnythingIsWritten() {
        doThrowUnauthorized();

        assertThatThrownBy(() -> svc.run(pipeline(canvas("conn-1")), "alice", ctx))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("未授权");

        verify(runMapper, never()).insert(any());
        verify(k8s, never()).createRun(anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), anyBoolean());
    }

    @Test
    void theRegistryConnectionOfAnImageNodeIsCheckedAsARegistryConnection() {
        String withImage = """
                {"nodes":[{"id":"build","type":"image","config":{"registryConnectionId":"reg-9"}}],
                 "edges":[]}""";
        when(auth.hatTenantId()).thenReturn("t1");

        svc.run(pipeline(withImage), "alice", ctx);

        // null = 不校验类型：镜像节点只要求「是一条被授权的 registry 连接」
        verify(credentials).resolveForPipeline("reg-9", null, "t1");
    }

    @Test
    void theStoredRunCarriesTheTriggeredByAndTheCanvas() {
        svc.run(pipeline(canvas("conn-1")), "alice", ctx);

        ArgumentCaptor<PlatformPipelineRun> cap = ArgumentCaptor.forClass(PlatformPipelineRun.class);
        verify(runMapper).insert(cap.capture());
        assertThat(cap.getValue().getTriggeredBy()).isEqualTo("alice");
        assertThat(cap.getValue().getStatus()).isEqualTo("RUNNING");
        assertThat(cap.getValue().getPipelineId()).isEqualTo("p1");
        assertThat(cap.getValue().getClusterId()).isEqualTo("c1");
        assertThat(cap.getValue().getNamespace()).isEqualTo("ns1");
        // 触发那一刻的画布也要留在这一行上：集群对象归档后就被删了，而流水线**之后还会被改** ——
        // 没有它，历史运行详情画的是今天的流水线（节点名、镜像、甚至已删的节点全对不上）
        assertThat(cap.getValue().getCanvasJson()).isEqualTo(canvas("conn-1"));
    }

    // ---- 取消 ----

    @Test
    void cancelIsForwardedToK8sServerWhichOwnsTheCaptureThenCancelOrdering() {
        // ⭐ spec §4.2「先抓后取消」的**顺序**在 k8s-server 的 PipelineRunFinisher 里 ——
        //    那条链路有集群连接，也只有它能在 Pod 被删之前把日志抓下来。
        when(runMapper.selectByRunName("r1")).thenReturn(running("r1"));

        svc.cancel("r1", ctx);

        // ⚠️ 「先抓日志、再取消」的**编排在 k8s-server 侧**（PipelineRunFinisher.cancel）——
        //    那边才有集群连接。platform-api 这一层只做归属校验与转发，
        //    所以这里只需断言它把取消转了出去。
        verify(k8s).cancelRun("c1", "t1", "ns1", "r1");
    }

    @Test
    void cancellingAnUnknownRunIsRefused() {
        when(runMapper.selectByRunName("nope")).thenReturn(null);

        assertThatThrownBy(() -> svc.cancel("nope", ctx))
                .isInstanceOf(CloudPlatformException.class);

        verify(k8s, never()).cancelRun(anyString(), any(), anyString(), anyString());
    }

    @Test
    void theRunNameIsGloballyUniqueSoTheDbIndexNeverCollides() {
        // uk_run_name 是**全局**唯一（不按 cluster/namespace 分组），所以名字必须带时间戳 + 随机后缀
        String a = svc.run(pipeline(canvas("c")), "alice", ctx);
        String b = svc.run(pipeline(canvas("c")), "alice", ctx);

        assertThat(a).isNotEqualTo(b);
        assertThat(a).matches("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$");   // K8s 对象名的规矩
    }

    // ---- 历史 ----

    @Test
    void historyIsReadFromTheDatabaseOnly() {
        // 集群里的对象归档后已经删了 —— 读历史一律读库（spec §4.3）
        when(runMapper.listByPipeline("p1", "c1", "ns1")).thenReturn(List.of(running("r1")));

        assertThat(svc.list("p1", ctx)).extracting(PlatformPipelineRun::getRunName).containsExactly("r1");
        verify(k8s, never()).getRun(anyString(), any(), anyString(), anyString());
    }

    private static PlatformPipelineRun running(String runName) {
        PlatformPipelineRun r = new PlatformPipelineRun();
        r.setId("run-1");
        r.setRunName(runName);
        r.setPipelineId("p1");
        r.setClusterId("c1");
        r.setNamespace("ns1");
        r.setStatus("RUNNING");
        return r;
    }

    private void doThrowUnauthorized() {
        org.mockito.Mockito.doThrow(new CloudPlatformException(EnumResponseType.CREDENTIAL_INVALID,
                        "该连接未授权在流水线里使用：conn-1"))
                .when(credentials).resolveForPipeline(any(), any(), any());
    }

    // ---- 日志（两种数据源，分界线是「归档了没有」）----

    @Test
    void anArchivedRunsLogsComeFromTheDatabaseNotTheCluster() {
        // 归档之后集群里的对象与 Pod 都删了 —— 库是唯一存续形式（spec §4.3）
        when(runMapper.selectByRunName("r1")).thenReturn(archived("r1"));
        PlatformPipelineRunLog line = new PlatformPipelineRunLog();
        line.setRunId("run-1");
        line.setNodeName("clone");
        line.setContent("hello from the archive");
        line.setTruncated(0);
        when(logMapper.listByRunId("run-1")).thenReturn(List.of(line));

        String logs = svc.logs("r1", null, ctx, 1024, null).text();

        assertThat(logs).isEqualTo("hello from the archive");
        verify(k8s, never()).steps(anyString(), any(), anyString(), anyString());
    }

    @Test
    void aRunningRunsLogsAreFetchedLiveThroughTheExistingPodChannel() {
        when(runMapper.selectByRunName("r1")).thenReturn(running("r1"));
        when(k8s.steps("c1", "t1", "ns1", "r1")).thenReturn(
                List.of(new com.coding.common.models.pipeline.PipelineStepRefDTO(
                        "clone", "RUNNING", "clone-pod", List.of("step-run"), null)));
        when(k8s.podLog("c1", "t1", "ns1", "clone-pod", "step-run", 1024, null))
                .thenReturn(new K8sPipelineClient.CappedLog("live!\n", false, 6));

        assertThat(svc.logs("r1", null, ctx, 1024, null).text()).isEqualTo("live!\n");
        verify(logMapper, never()).listByRunId(anyString());   // 没落库
    }

    @Test
    void aTruncatedArchivedLogSaysSoInTheOutput() {
        when(runMapper.selectByRunName("r1")).thenReturn(archived("r1"));
        PlatformPipelineRunLog line = new PlatformPipelineRunLog();
        line.setRunId("run-1");
        line.setNodeName("build");
        line.setContent("x");
        line.setTruncated(1);
        when(logMapper.listByRunId("run-1")).thenReturn(List.of(line));

        assertThat(svc.logs("r1", null, ctx, 1024, null).text()).contains("已截断");
    }

    @Test
    void logsOfAnUnknownRunAreRefused() {
        when(runMapper.selectByRunName("nope")).thenReturn(null);
        assertThatThrownBy(() -> svc.logs("nope", null, ctx, 1024, null).text())
                .isInstanceOf(CloudPlatformException.class);
    }

    // ---- 增量（⭐ 2026-10-16：日志要能自己往下滚，而不是让人反复点节点）----

    /** 一个还在跑的运行 + 一个节点一个容器 —— 增量那几条测试的公共布景。 */
    private void aRunningNodeWithOneContainer() {
        when(runMapper.selectByRunName("r1")).thenReturn(running("r1"));
        when(k8s.steps("c1", "t1", "ns1", "r1")).thenReturn(List.of(
                new com.coding.common.models.pipeline.PipelineStepRefDTO(
                        "build", "RUNNING", "build-pod", List.of("step-build"), null)));
    }

    private static K8sPipelineClient.CappedLog cap(String content) {
        return new K8sPipelineClient.CappedLog(content, false, content.length());
    }

    @Test
    void aLiveLogsBatchComesBackWithTheTimestampPrefixStrippedAndACursor() {
        // k8s-server 恒带 `<RFC3339Nano>\t` 前缀（那是增量游标的依据），但它是**实现细节** ——
        // 漏到前端就等于让每个调用方各自重写一遍正则与去重
        aRunningNodeWithOneContainer();
        when(k8s.podLog("c1", "t1", "ns1", "build-pod", "step-build", 1024, null)).thenReturn(cap(
                "2026-10-16T07:16:32.598034681Z 第一行\n"
                        + "2026-10-16T07:16:35.000000000Z 第二行\n"));

        PipelineRunLogDTO logs = svc.logs("r1", "build", ctx, 1024, null);

        assertThat(logs.text()).isEqualTo("第一行\n第二行\n");
        assertThat(logs.cursor()).isEqualTo("2026-10-16T07:16:35.000000000Z");
        assertThat(logs.live()).isTrue();
    }

    @Test
    void theNextPollAsksForNewLinesOnlyAndDropsTheRepeatedBoundaryLine() {
        // ⚠️ k8s 的 sinceTime 是「该时刻之后」且**边界那行会重复来一次** ——
        //    不滤的话每轮都多出一行"上一轮的尾巴"
        aRunningNodeWithOneContainer();
        when(k8s.podLog("c1", "t1", "ns1", "build-pod", "step-build", 1024,
                "2026-10-16T07:16:35.000000000Z")).thenReturn(cap(
                "2026-10-16T07:16:35.000000000Z 第二行\n"
                        + "2026-10-16T07:16:40.123456789Z 第三行\n"));

        PipelineRunLogDTO logs = svc.logs("r1", "build", ctx, 1024, "2026-10-16T07:16:35.000000000Z");

        assertThat(logs.text()).isEqualTo("第三行\n");
        assertThat(logs.cursor()).isEqualTo("2026-10-16T07:16:40.123456789Z");
        verify(k8s).podLog("c1", "t1", "ns1", "build-pod", "step-build", 1024,
                "2026-10-16T07:16:35.000000000Z");
    }

    @Test
    void aLogThatComesBackWithoutTimestampsFallsBackToWholesale() {
        // 集群侧没给时间戳时增量没有依据 —— **必须退回整段**（live=false）：
        // 否则调用方按"追加"把同一份日志一遍遍接上去，屏幕上越长越离谱
        aRunningNodeWithOneContainer();
        when(k8s.podLog("c1", "t1", "ns1", "build-pod", "step-build", 1024, null))
                .thenReturn(cap("没有时间戳的一行\n"));

        PipelineRunLogDTO logs = svc.logs("r1", "build", ctx, 1024, null);

        assertThat(logs.live()).isFalse();
        assertThat(logs.cursor()).isNull();
        assertThat(logs.text()).isEqualTo("没有时间戳的一行\n");
    }

    @Test
    void aCursorAgainstATimestamplessLogReFetchesFromTheStart() {
        // 已经带着游标了，却发现这一批没有时间戳 —— 手上这份是**残缺**的，
        // 不能说"这就是全量"，得回头重新整段取一次
        aRunningNodeWithOneContainer();
        when(k8s.podLog("c1", "t1", "ns1", "build-pod", "step-build", 1024, "2026-10-16T07:16:35Z"))
                .thenReturn(cap("半截\n"));
        when(k8s.podLog("c1", "t1", "ns1", "build-pod", "step-build", 1024, null))
                .thenReturn(cap("从头来的整段\n"));

        PipelineRunLogDTO logs = svc.logs("r1", "build", ctx, 1024, "2026-10-16T07:16:35Z");

        assertThat(logs.text()).isEqualTo("从头来的整段\n");
        assertThat(logs.live()).isFalse();
    }

    @Test
    void aTabInTheContentIsNotMistakenForTheTimestampSeparator() {
        // ⚠️ 「找第一个制表符」那种写法会把含制表符的行从中间劈开，前半个当时间戳、后半个当内容。
        //    分隔符可能是制表符（kubelet 原样）也可能是空格（中间转发规整过），两种都认。
        assertThat(PipelineRunService.splitLogLine("a\tb")).containsExactly("", "a\tb");
        assertThat(PipelineRunService.splitLogLine("2026-10-16T07:16:32Z\thello"))
                .containsExactly("2026-10-16T07:16:32Z", "hello");
        assertThat(PipelineRunService.splitLogLine("2026-10-16T07:16:32Z hello"))
                .containsExactly("2026-10-16T07:16:32Z", "hello");
        // 应用自己打在行首的 +08:00 时间戳**不许**当 k8s 的剥掉
        assertThat(PipelineRunService.splitLogLine("2026-10-16T15:16:32+08:00 hello"))
                .containsExactly("", "2026-10-16T15:16:32+08:00 hello");
    }

    @Test
    void fractionalSecondsArePaddedBeforeComparingSoStringOrderIsChronological() {
        // ⚠️ 直接比字符串：`…:00.5Z` 与 `…:00.45Z` 会得出前者更小，而按时间它更大
        assertThat(PipelineRunService.normTs("2026-10-16T07:16:00.5Z"))
                .isGreaterThan(PipelineRunService.normTs("2026-10-16T07:16:00.45Z"));
        assertThat(PipelineRunService.normTs("2026-10-16T07:16:00Z"))
                .isLessThan(PipelineRunService.normTs("2026-10-16T07:16:01Z"));
    }

    private static PlatformPipelineRun archived(String runName) {
        PlatformPipelineRun r = running(runName);
        r.setStatus("SUCCEEDED");
        return r;
    }

    /** 带**触发时那份画布**的运行：段头显示名字就靠它（一个起了名、一个没起） */
    private static PlatformPipelineRun archivedWithCanvas(String runName) {
        PlatformPipelineRun r = archived(runName);
        r.setCanvasJson(CANVAS_WITH_NAMES);
        return r;
    }

    private static PlatformPipelineRun runningWithCanvas(String runName) {
        PlatformPipelineRun r = running(runName);
        r.setCanvasJson(CANVAS_WITH_NAMES);
        return r;
    }

    private static final String CANVAS_WITH_NAMES =
            "{\"nodes\":[{\"id\":\"git-l8x31\",\"type\":\"git\",\"name\":\"拉取代码\",\"config\":{}},"
          + "{\"id\":\"image-m2k9\",\"type\":\"image\",\"config\":{}}],\"edges\":[]}";

    private static PlatformPipelineRunLog logLine(String nodeName, String content) {
        PlatformPipelineRunLog l = new PlatformPipelineRunLog();
        l.setRunId("run-1");
        l.setNodeName(nodeName);
        l.setContent(content);
        l.setTruncated(0);
        return l;
    }

    // ---- 「全部」日志的段头：给人看的那一行用**名字**，id 不是给人看的 ----

    @Test
    void theAllNodesLogHeaderShowsTheNodesNameNotItsId() {
        when(runMapper.selectByRunName("r1")).thenReturn(archivedWithCanvas("r1"));
        when(logMapper.listByRunId("run-1")).thenReturn(List.of(
                logLine("git-l8x31", "cloning\n"), logLine("image-m2k9", "building\n")));

        String out = svc.logs("r1", null, ctx, 1024, null).text();

        assertThat(out).contains("===== 拉取代码 =====");
        assertThat(out).doesNotContain("===== git-l8x31 =====");
    }

    @Test
    void aNodeWithoutANameKeepsShowingItsIdInTheHeader() {
        // 没起名的节点**不能出现空段头** —— 退回 id
        when(runMapper.selectByRunName("r1")).thenReturn(archivedWithCanvas("r1"));
        when(logMapper.listByRunId("run-1")).thenReturn(List.of(
                logLine("git-l8x31", "cloning\n"), logLine("image-m2k9", "building\n")));

        assertThat(svc.logs("r1", null, ctx, 1024, null).text()).contains("===== image-m2k9 =====");
    }

    @Test
    void anOldRunWithoutACanvasFallsBackToIdsInTheHeader() {
        // 本功能上线前触发的运行没留画布（也没有名字）—— 段头退回 id，不是空白
        when(runMapper.selectByRunName("r1")).thenReturn(archived("r1"));
        when(logMapper.listByRunId("run-1")).thenReturn(List.of(
                logLine("git-l8x31", "cloning\n"), logLine("image-m2k9", "building\n")));

        assertThat(svc.logs("r1", null, ctx, 1024, null).text()).contains("===== git-l8x31 =====");
    }

    @Test
    void aRunningRunsAllNodesHeaderAlsoUsesTheName() {
        // 还在跑的那条路（现抓）也要一样 —— 两条路的段头不能长得不一样
        when(runMapper.selectByRunName("r1")).thenReturn(runningWithCanvas("r1"));
        when(k8s.steps("c1", "t1", "ns1", "r1")).thenReturn(List.of(
                new com.coding.common.models.pipeline.PipelineStepRefDTO(
                        "git-l8x31", "RUNNING", "git-pod", List.of("step-run"), null),
                new com.coding.common.models.pipeline.PipelineStepRefDTO(
                        "image-m2k9", "RUNNING", "image-pod", List.of("step-build"), null)));
        when(k8s.podLog(anyString(), any(), anyString(), anyString(), anyString(), anyInt(), any()))
                .thenReturn(new K8sPipelineClient.CappedLog("x\n", false, 2));

        String out = svc.logs("r1", null, ctx, 1024, null).text();

        assertThat(out).contains("===== 拉取代码 =====");
        assertThat(out).contains("===== image-m2k9 =====");
    }

    @Test
    void filteringByOneNodeStillUsesTheIdNotTheName() {
        // ⚠️ 段头是给人看的，**过滤键仍是 id**（库里的 node_name 就是 id）——
        //    拿名字去过滤会一条都匹配不上（前端传的也是 id）
        when(runMapper.selectByRunName("r1")).thenReturn(archivedWithCanvas("r1"));
        when(logMapper.listByRunId("run-1")).thenReturn(List.of(
                logLine("git-l8x31", "cloning\n"), logLine("image-m2k9", "building\n")));

        assertThat(svc.logs("r1", "git-l8x31", ctx, 1024, null).text()).isEqualTo("cloning\n");
        assertThat(svc.logs("r1", "拉取代码", ctx, 1024, null).text()).isEmpty();
    }

    @Test
    void anOldArchiveStillFiltersByNodeIdWhenItsRowsCarryTheTaskRunName() {
        // ⚠️ 兼容：2026-10-16 之前归档的运行，日志行是按 **TaskRun 名** 存的
        //    （`<运行名>-<节点 id>`，根因见 PipelineRunOperations.nodeNameOf）。
        //    只认节点 id 的话，点这些老运行的节点日志会一条都取不到 ——
        //    用户看到的还是「只能在『全部』里看到」，等于没修。
        when(runMapper.selectByRunName("r1")).thenReturn(archivedWithCanvas("r1"));
        when(logMapper.listByRunId("run-1")).thenReturn(List.of(
                logLine("r1-git-l8x31", "old cloning\n"), logLine("image-m2k9", "new building\n")));

        assertThat(svc.logs("r1", "git-l8x31", ctx, 1024, null).text()).isEqualTo("old cloning\n");
        assertThat(svc.logs("r1", "image-m2k9", ctx, 1024, null).text()).isEqualTo("new building\n");
        // 别名不能把别的节点也捞进来：按节点过滤就该只有那一个节点
        assertThat(svc.logs("r1", "image-m2k9", ctx, 1024, null).text()).doesNotContain("old cloning");
    }

    // ---- 手动删一条运行记录（用户 2026-10-16 要的「运行历史删除」）----
    //
    // ⚠️ 删的**只是库里的行**：归档那一刻集群里的对象（与 Pod）就已经删了 ——
    //    所以这是"把历史记录抹掉"，不可恢复（库是唯一留存，日志与 YAML 都在这里）。

    @Test
    void deletingARunRemovesItsLogsFirstThenTheRun() {
        // ⭐ 顺序有意义：本仓不加外键、级联靠代码 —— 反了就会留下一堆没人认领的日志行
        //    （表会一直长，而没有任何入口能看到它们）
        when(runMapper.selectByRunName("r1")).thenReturn(archived("r1"));

        svc.delete("r1");

        InOrder order = inOrder(logMapper, runMapper);
        order.verify(logMapper).deleteByRunId("run-1");
        order.verify(runMapper).deleteById("run-1");
    }

    @Test
    void aRunningRunCannotBeDeleted() {
        // 还在跑的那条**先取消**：它的归档回写还要落在这行上（删了就是"归档落在不存在的行"），
        // 而且集群里的对象还在跑 —— 用户想停的是那次运行，不是它的历史记录
        when(runMapper.selectByRunName("r1")).thenReturn(running("r1"));

        assertThatThrownBy(() -> svc.delete("r1"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("先取消");

        verify(runMapper, never()).deleteById(anyString());
        verify(logMapper, never()).deleteByRunId(anyString());
    }

    @Test
    void deletingAnUnknownRunIsRefused() {
        assertThatThrownBy(() -> svc.delete("nope"))
                .isInstanceOf(CloudPlatformException.class);

        verify(runMapper, never()).deleteById(anyString());
    }

    @Test
    void deletingChecksTheNamespaceBoundaryFromTheStoredRow() {
        // ⭐ run 名是调用方给的，边界必须取**库里那行**（与 logs / get 同一条）
        when(runMapper.selectByRunName("r1")).thenReturn(archived("r1"));
        org.mockito.Mockito.doThrow(new CloudPlatformException(
                        EnumResponseType.NAMESPACE_NOT_ACCESSIBLE, "无权访问该命名空间：ns1"))
                .when(access).assertAccessible(any(), any());

        assertThatThrownBy(() -> svc.delete("r1")).isInstanceOf(CloudPlatformException.class);

        verify(runMapper, never()).deleteById(anyString());
        verify(logMapper, never()).deleteByRunId(anyString());
    }

    // ---- 归属边界（run 名是调用方给的，不带边界）----

    @Test
    void anArchivedRunsLogsAreRefusedForATenantThatDoesNotOwnTheNamespace() {
        // ⭐ 已归档的日志路径**不经过集群** —— k8s-server 侧的分配表兜不住它，
        //    只有这里拦得住。日志按 spec §10.1.2 是可能含敏感内容的东西。
        when(runMapper.selectByRunName("r1")).thenReturn(archived("r1"));
        org.mockito.Mockito.doThrow(new CloudPlatformException(
                        EnumResponseType.NAMESPACE_NOT_ACCESSIBLE, "无权访问该命名空间：ns1"))
                .when(access).assertAccessible(any(), any());

        assertThatThrownBy(() -> svc.logs("r1", null, ctx, 1024, null).text())
                .isInstanceOf(CloudPlatformException.class);
        verify(logMapper, never()).listByRunId(anyString());
        verify(k8s, never()).steps(anyString(), any(), anyString(), anyString());
    }

    @Test
    void theAccessCheckUsesTheStoredLocationNotTheCallers() {
        // run 名来自请求，边界必须取**库里那行**
        when(runMapper.selectByRunName("r1")).thenReturn(archived("r1"));

        svc.get("r1");

        verify(access).assertAccessible("c1", "ns1");
    }

    // ---- 每个节点「现在」的落点（运行详情的 DAG 着色）----

    @Test
    void theStepsOfARunningRunAreFetchedLive() {
        when(runMapper.selectByRunName("r1")).thenReturn(running("r1"));
        when(k8s.steps("c1", "t1", "ns1", "r1")).thenReturn(List.of(
                new com.coding.common.models.pipeline.PipelineStepRefDTO(
                        "clone", "SUCCEEDED", "clone-pod", List.of("step-run"), null)));

        assertThat(svc.steps("r1", ctx))
                .extracting(com.coding.common.models.pipeline.PipelineStepRefDTO::nodeName)
                .containsExactly("clone");
    }

    @Test
    void anArchivedRunHasNoLiveSteps() {
        // 归档之后 TaskRun 与 Pod 早被删了 —— 那时节点状态在 run 行的 stepsJson 里（归档那一刻的快照）。
        // 本方法只回答「现在」，**不去把两种来源拼成同一个形状**：前端按 run 的状态选一个来源
        when(runMapper.selectByRunName("r1")).thenReturn(archived("r1"));

        assertThat(svc.steps("r1", ctx)).isEmpty();
        verify(k8s, never()).steps(anyString(), any(), anyString(), anyString());
    }

    @Test
    void theStepsOfAnUnknownRunAreRefused() {
        when(runMapper.selectByRunName("nope")).thenReturn(null);

        assertThatThrownBy(() -> svc.steps("nope", ctx))
                .isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void theStepsOfAnotherTenantsRunAreRefused() {
        when(runMapper.selectByRunName("r1")).thenReturn(running("r1"));
        org.mockito.Mockito.doThrow(new CloudPlatformException(
                        EnumResponseType.NAMESPACE_NOT_ACCESSIBLE, "无权访问该命名空间：ns1"))
                .when(access).assertAccessible(any(), any());

        assertThatThrownBy(() -> svc.steps("r1", ctx)).isInstanceOf(CloudPlatformException.class);
        verify(k8s, never()).steps(anyString(), any(), anyString(), anyString());
    }

    // ---- YAML（还是那条「归档了没有」的分界线）----

    @Test
    void theYamlOfARunningRunComesFromTheClusterObject() {
        when(runMapper.selectByRunName("r1")).thenReturn(running("r1"));
        when(k8s.runYaml("c1", "t1", "ns1", "r1")).thenReturn("kind: PipelineRun\n");

        assertThat(svc.runYaml("r1", ctx)).isEqualTo("kind: PipelineRun\n");
    }

    @Test
    void anArchivedRunsYamlIsTheOneCapturedAtArchiveTime() {
        // ⭐ 归档后集群对象已经删了。用**归档那一刻抓下来的**那一份 ——
        // 拿今天的画布重渲染会画出一份当年没跑过的东西（而且对引用了连接的流水线根本渲染不出来）
        PlatformPipelineRun row = archived("r1");
        row.setRunYaml("kind: PipelineRun   # 当年那一份\n");
        when(runMapper.selectByRunName("r1")).thenReturn(row);

        assertThat(svc.runYaml("r1", ctx)).contains("当年那一份");
        verify(k8s, never()).runYaml(anyString(), any(), anyString(), anyString());
    }

    @Test
    void anArchivedRunWithoutACapturedYamlReturnsNothingInsteadOfMakingOneUp() {
        // 早于本功能上线的运行没留下 YAML —— 返回空，由前端说「没有留下」，
        // **绝不**拿今天的画布编一份出来冒充。
        // ⚠️ 这里要把集群那一路**桩上**：不桩的话「走错了分支」也会返回 null，测试就白写了
        when(runMapper.selectByRunName("r1")).thenReturn(archived("r1"));
        when(k8s.runYaml(anyString(), any(), anyString(), anyString()))
                .thenReturn("今天重新渲染出来的一份\n");

        assertThat(svc.runYaml("r1", ctx)).isNull();
    }

    @Test
    void theYamlOfAnUnknownRunIsRefused() {
        when(runMapper.selectByRunName("nope")).thenReturn(null);

        assertThatThrownBy(() -> svc.runYaml("nope", ctx)).isInstanceOf(CloudPlatformException.class);
    }

    // ---- 触发时的节点屏蔽 ----

    @Test
    void theMaskedClosureIsPersistedAndTheTrimmedCanvasIsSent() {
        when(k8s.createRun(anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(new PipelineRunDTO());
        String canvas = """
                {"nodes":[
                  {"id":"a","type":"shell","config":{}},
                  {"id":"b","type":"shell","config":{}},
                  {"id":"c","type":"shell","config":{}}
                ],"edges":[{"from":"a","to":"b"},{"from":"b","to":"c"}]}""";

        // 只屏蔽 c（叶子）
        svc.run(pipeline(canvas), "alice", ctx, List.of("c"));

        ArgumentCaptor<PlatformPipelineRun> row = ArgumentCaptor.forClass(PlatformPipelineRun.class);
        verify(runMapper).insert(row.capture());
        assertThat(row.getValue().getMaskedNodesJson()).isEqualTo("[\"c\"]");
        assertThat(row.getValue().getCanvasJson()).as("库里存完整画布").isEqualTo(canvas);
        assertThat(row.getValue().getWorkspacePvc()).startsWith("platform-pipeline-ws-");

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(k8s).createRun(anyString(), any(), anyString(), anyString(), sent.capture(), any(),
                anyString(), anyBoolean());
        assertThat(sent.getValue()).doesNotContain("\"id\":\"c\"").contains("\"id\":\"a\"");
    }

    @Test
    void maskingAMiddleNodeDropsItsWholeSubtreeFromTheRun() {
        when(k8s.createRun(anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(new PipelineRunDTO());
        String canvas = """
                {"nodes":[{"id":"a","type":"shell","config":{}},
                          {"id":"b","type":"shell","config":{}},
                          {"id":"c","type":"shell","config":{}}],
                 "edges":[{"from":"a","to":"b"},{"from":"b","to":"c"}]}""";

        svc.run(pipeline(canvas), "alice", ctx, List.of("b"));

        ArgumentCaptor<PlatformPipelineRun> row = ArgumentCaptor.forClass(PlatformPipelineRun.class);
        verify(runMapper).insert(row.capture());
        assertThat(row.getValue().getMaskedNodesJson()).isEqualTo("[\"b\",\"c\"]");
    }

    @Test
    void maskingEveryRunnableNodeIsRefused() {
        String canvas = """
                {"nodes":[{"id":"a","type":"shell","config":{}}],"edges":[]}""";
        assertThatThrownBy(() -> svc.run(pipeline(canvas), "alice", ctx, List.of("a")))
                .isInstanceOf(CloudPlatformException.class);
        verify(runMapper, never()).insert(any());
    }

    @Test
    void connectionsAreResolvedFromTheTrimmedCanvasOnly() {
        // 被屏蔽节点引用的连接不该因为「这次不跑」还要求授权
        when(k8s.createRun(anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(new PipelineRunDTO());
        String canvas = """
                {"nodes":[
                  {"id":"a","type":"git","config":{"connectionId":"conn-1"}},
                  {"id":"b","type":"git","config":{"connectionId":"conn-2"}}
                ],"edges":[]}""";
        when(auth.hatTenantId()).thenReturn("t1");

        svc.run(pipeline(canvas), "alice", ctx, List.of("b"));

        verify(credentials).resolveForPipeline("conn-1", "GIT", "t1");
        verify(credentials, never()).resolveForPipeline("conn-2", "GIT", "t1");
    }

    @Test
    void noMaskMeansNoMaskedColumnAndTheFullCanvasGoesOut() {
        when(k8s.createRun(anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(new PipelineRunDTO());
        String canvas = """
                {"nodes":[{"id":"a","type":"shell","config":{}}],"edges":[]}""";

        svc.run(pipeline(canvas), "alice", ctx, List.of());

        ArgumentCaptor<PlatformPipelineRun> row = ArgumentCaptor.forClass(PlatformPipelineRun.class);
        verify(runMapper).insert(row.capture());
        assertThat(row.getValue().getMaskedNodesJson()).isNull();
    }
}
