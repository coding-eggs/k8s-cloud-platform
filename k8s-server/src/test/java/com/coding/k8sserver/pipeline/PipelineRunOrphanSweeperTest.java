package com.coding.k8sserver.pipeline;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.PipelineRunDTO;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.mapper.system.PlatformPipelineRunMapper;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.data.models.system.PlatformPipelineRun;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.pipelinerun.PipelineRunOperations;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 流水线运行的两个方向的对账。
 *
 * <h2>① 集群 → 库（每个集群只做一次）</h2>
 * 重启后台账是空的 —— 靠它把「重启前在跑」的运行补盯上；扫过就不再扫，
 * 否则就成了每 5 分钟轮询所有集群。连不上的下一拍再试，没有 Tekton 的算扫过。
 *
 * <h2>② 库 → 集群（每一拍）</h2>
 * 库里仍是 RUNNING、集群里却已经没人在管的那些行：对象没了 → 结案；对象终结了但没归档 → 现在归档；
 * 对象还在跑但台账里没有 → 补 watch（**只补 watch 不结案**，它跑得好好的）。
 */
class PipelineRunOrphanSweeperTest {

    private static final Duration GRACE = Duration.ofMinutes(2);
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    private final PlatformPipelineRunMapper runMapper = mock(PlatformPipelineRunMapper.class);
    private final K8sClusterMapper clusterMapper = mock(K8sClusterMapper.class);
    private final KubernetesOperationsFactory factory = mock(KubernetesOperationsFactory.class);
    private final PipelineRunWatchManager watch = mock(PipelineRunWatchManager.class);
    private final PipelineRunFinisher finisher = mock(PipelineRunFinisher.class);
    private final PipelineArchiveService archive = mock(PipelineArchiveService.class);
    private final PipelineRunOperations ops = mock(PipelineRunOperations.class);

    private PipelineRunOrphanSweeper sweeper() {
        return new PipelineRunOrphanSweeper(runMapper, clusterMapper, factory, watch, finisher, archive,
                GRACE, true);
    }

    /** `getAdminNamespacedOperation` 是 `<T> NamespacedOperations<T>`，泛型推断在 when() 里过不去 —— 用 doReturn。 */
    private void opsAre(PipelineRunOperations runOps) {
        doReturn(runOps).when(factory).getAdminNamespacedOperation(any(ResourceType.class), anyString());
    }

    private static K8sCluster cluster(String clusterId) {
        return cluster(clusterId, K8sCluster.ClusterStatus.CONNECTED);
    }

    private static K8sCluster cluster(String clusterId, K8sCluster.ClusterStatus status) {
        K8sCluster c = new K8sCluster();
        c.setClusterId(clusterId);
        c.setEnabled(1);
        c.setStatus(status);
        return c;
    }

    private static PlatformPipelineRun row(String runName, Instant createdAt) {
        PlatformPipelineRun r = new PlatformPipelineRun();
        r.setId("id-" + runName);
        r.setRunName(runName);
        r.setClusterId("c1");
        r.setNamespace("ns1");
        r.setStatus("RUNNING");
        r.setCreatedAt(Date.from(createdAt));
        return r;
    }

    private static PipelineRunDTO object(String status, String startTime) {
        PipelineRunDTO dto = new PipelineRunDTO();
        dto.setName("r1");
        dto.setNamespace("ns1");
        dto.setStatus(status);
        dto.setStartTime(startTime);
        return dto;
    }

    @Test
    void aRunWhoseObjectIsGoneIsClosedAsLost() {
        opsAre(ops);
        when(runMapper.listRunning()).thenReturn(List.of(row("gone", NOW.minus(Duration.ofMinutes(10)))));
        when(ops.get("ns1", "gone")).thenReturn(null);

        sweeper().sweep(NOW);

        verify(archive).markLost("gone");
        verify(watch, never()).watch(anyString(), anyString(), anyString(), any());
    }

    @Test
    void aTerminatedObjectThatWasNeverArchivedIsArchivedNow() {
        // ⭐ 只补 watch 是**不够的**：watch 不补发已经发生的事件。
        //    对象已经终结、库还停在 RUNNING（k8s-server 在它终结的那段时间里重启过）→ 直接归档。
        opsAre(ops);
        when(runMapper.listRunning()).thenReturn(List.of(row("r1", NOW.minus(Duration.ofMinutes(10)))));
        when(ops.get("ns1", "r1")).thenReturn(object("FAILED", "2026-10-20T11:50:00Z"));

        sweeper().sweep(NOW);

        verify(finisher).finish("c1", "ns1", "r1", false);
        verify(archive, never()).markLost(anyString());
        verify(watch, never()).watch(anyString(), anyString(), anyString(), any());
    }

    @Test
    void aRunningObjectWithoutAWatchGetsOneBack() {
        opsAre(ops);
        when(runMapper.listRunning()).thenReturn(List.of(row("r1", NOW.minus(Duration.ofMinutes(10)))));
        when(ops.get("ns1", "r1")).thenReturn(object("RUNNING", "2026-10-20T11:55:00Z"));

        sweeper().sweep(NOW);

        // startedAt 取对象上的 startTime（不是 now）—— 否则超时时钟会被重置
        verify(watch).watch("c1", "ns1", "r1", Instant.parse("2026-10-20T11:55:00Z"));
        verify(archive, never()).markLost(anyString());
        verify(finisher, never()).finish(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void aRunningObjectThatIsAlreadyTrackedIsLeftAlone() {
        opsAre(ops);
        when(runMapper.listRunning()).thenReturn(List.of(row("r1", NOW.minus(Duration.ofMinutes(10)))));
        when(ops.get("ns1", "r1")).thenReturn(object("RUNNING", "2026-10-20T11:55:00Z"));
        when(watch.snapshot()).thenReturn(Map.of("c1/ns1/r1",
                new PipelineRunWatchManager.ActiveRun("c1", "ns1", "r1", NOW)));

        sweeper().sweep(NOW);

        verify(watch, never()).watch(anyString(), anyString(), anyString(), any());
    }

    @Test
    void aFreshRowIsGivenTimeForItsObjectToAppear() {
        // ⚠️ 创建是「先落库、再建对象」两步。中间那一瞬没有对象，不能判死 ——
        //    否则会把一条正在创建的运行标成 FAILED，而它随后照样会跑起来。
        when(runMapper.listRunning()).thenReturn(List.of(row("r1", NOW.minus(Duration.ofSeconds(30)))));

        sweeper().sweep(NOW);

        verifyNoInteractions(factory, finisher, archive);
    }

    @Test
    void aRowWithoutACreationTimeIsReconciledRatherThanSkippedForever() {
        PlatformPipelineRun noCreatedAt = row("r1", NOW);
        noCreatedAt.setCreatedAt(null);
        opsAre(ops);
        when(runMapper.listRunning()).thenReturn(List.of(noCreatedAt));
        when(ops.get("ns1", "r1")).thenReturn(null);

        sweeper().sweep(NOW);

        verify(archive).markLost("r1");
    }

    @Test
    void oneBadRowDoesNotStopTheRest() {
        // 一个集群连不上不能把其余行带走（与启动扫描、工作区回收同形）
        opsAre(ops);
        when(runMapper.listRunning()).thenReturn(List.of(
                row("bad", NOW.minus(Duration.ofMinutes(10))),
                row("gone", NOW.minus(Duration.ofMinutes(10)))));
        when(ops.get("ns1", "bad")).thenThrow(new RuntimeException("cluster down"));
        when(ops.get("ns1", "gone")).thenReturn(null);

        sweeper().sweep(NOW);

        verify(archive).markLost("gone");
    }

    @Test
    void theGraceCheckIsPureAndEasyToReasonAbout() {
        // 到了宽限期就算「落库够久」；刚创建的还不行
        assertThat(PipelineRunOrphanSweeper.settledEnough(Date.from(NOW.minus(GRACE)), NOW, GRACE)).isTrue();
        assertThat(PipelineRunOrphanSweeper.settledEnough(Date.from(NOW.minus(Duration.ofSeconds(30))), NOW, GRACE))
                .isFalse();
    }

    @Test
    void disabledMeansNoDatabaseQueryAtAll() {
        new PipelineRunOrphanSweeper(runMapper, clusterMapper, factory, watch, finisher, archive,
                GRACE, false).sweep(NOW);

        verifyNoInteractions(runMapper, clusterMapper, factory, finisher, archive);
    }

    @Test
    void aDatabaseFailureJustSkipsThisSweep() {
        when(runMapper.listRunning()).thenThrow(new RuntimeException("db down"));

        sweeper().sweep(NOW);       // 不抛 —— 下一拍再来

        verifyNoInteractions(factory, finisher, archive);
    }

    // ---------------------------------------------------------------- ① 集群 → 库

    @Test
    void theFirstSweepReattachesWatchesToRunsThatWereInFlightAcrossARestart() {
        // ⭐ 重启后台账是空的 —— 只靠库反查（②）要等最多 5 分钟；
        //    第一拍从集群反查一次，把在跑的运行立刻补盯上
        when(clusterMapper.listAll()).thenReturn(List.of(cluster("c1")));
        when(runMapper.listRunning()).thenReturn(List.of());
        opsAre(ops);
        when(ops.listAll(null, null)).thenReturn(List.of(object("RUNNING", "2026-10-20T11:55:00Z")));

        sweeper().sweep(NOW);

        verify(watch).watch("c1", "ns1", "r1", Instant.parse("2026-10-20T11:55:00Z"));
    }

    @Test
    void aRunAlreadyInTheLedgerIsNotWatchedTwice() {
        when(clusterMapper.listAll()).thenReturn(List.of(cluster("c1")));
        when(runMapper.listRunning()).thenReturn(List.of());
        opsAre(ops);
        when(ops.listAll(null, null)).thenReturn(List.of(object("RUNNING", "2026-10-20T11:55:00Z")));
        when(watch.snapshot()).thenReturn(Map.of("c1/ns1/r1",
                new PipelineRunWatchManager.ActiveRun("c1", "ns1", "r1", NOW)));

        sweeper().sweep(NOW);

        verify(watch, never()).watch(anyString(), anyString(), anyString(), any());
    }

    @Test
    void eachClusterIsBootScannedOnlyOnce() {
        // ⭐ 不设这个闸门，就等于每 5 分钟把所有集群 list 一遍 —— 正是要避免的轮询
        when(clusterMapper.listAll()).thenReturn(List.of(cluster("c1")));
        when(runMapper.listRunning()).thenReturn(List.of());
        opsAre(ops);
        when(ops.listAll(null, null)).thenReturn(List.of());

        PipelineRunOrphanSweeper s = sweeper();
        s.sweep(NOW);
        s.sweep(NOW);

        verify(ops, times(1)).listAll(null, null);
    }

    @Test
    void aClusterWithoutTektonIsCountedAsScannedSoItIsNotRetriedForever() {
        // 404 = 这个集群没有 Tekton 的 CRD。没有可补盯的对象 —— 算扫过，
        // 否则每 5 分钟报一次（用户 2026-10-22 看到的正是这条警告）
        when(clusterMapper.listAll()).thenReturn(List.of(cluster("c1")));
        when(runMapper.listRunning()).thenReturn(List.of());
        opsAre(ops);
        when(ops.listAll(null, null)).thenThrow(new KubernetesClientException("Not Found", 404, null));

        PipelineRunOrphanSweeper s = sweeper();
        s.sweep(NOW);
        s.sweep(NOW);

        verify(ops, times(1)).listAll(null, null);
    }

    @Test
    void aClusterThatIsDownAtBootIsRetriedOnTheNextSweep() {
        // 连不上 ≠ 没有 Tekton：不进已扫集合，集群恢复后要能自己补上
        when(clusterMapper.listAll()).thenReturn(List.of(cluster("c1")));
        when(runMapper.listRunning()).thenReturn(List.of());
        opsAre(ops);
        when(ops.listAll(null, null))
                .thenThrow(new RuntimeException("cluster down"))
                .thenReturn(List.of());

        PipelineRunOrphanSweeper s = sweeper();
        s.sweep(NOW);
        s.sweep(NOW);

        verify(ops, times(2)).listAll(null, null);
    }

    @Test
    void aDisconnectedClusterIsNotTouchedUntilTheProberFlipsItBack() {
        // 已知连不上的集群每 5 分钟去撞一次 connect 超时是把调度线程白占掉 ——
        // 探活任务翻回 CONNECTED 之后，这一拍自然就补上了
        when(clusterMapper.listAll())
                .thenReturn(List.of(cluster("c1", K8sCluster.ClusterStatus.DISCONNECTED)))
                .thenReturn(List.of(cluster("c1")));
        when(runMapper.listRunning()).thenReturn(List.of());
        opsAre(ops);
        when(ops.listAll(null, null)).thenReturn(List.of());

        PipelineRunOrphanSweeper s = sweeper();
        s.sweep(NOW);       // 断开：不碰
        s.sweep(NOW);       // 恢复：补扫

        verify(ops, times(1)).listAll(null, null);
    }
}
