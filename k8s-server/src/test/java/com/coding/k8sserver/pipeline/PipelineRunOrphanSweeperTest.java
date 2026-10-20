package com.coding.k8sserver.pipeline;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.PipelineRunDTO;
import com.coding.data.mapper.system.PlatformPipelineRunMapper;
import com.coding.data.models.system.PlatformPipelineRun;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.pipelinerun.PipelineRunOperations;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 库里仍是 RUNNING、集群里却已经没人在管的那些行 —— 谁来结案。
 *
 * <p>⭐ 这是「运行中升级 Tekton」那个洞的兜底：watch 只在对象还在时才发事件，
 * 启动扫描也只列集群里**还在**的对象。k8s-server 恰好在那个窗口里重启过，
 * 两样都看不见，库里那行就永远 RUNNING、删除按钮永远不出现。
 *
 * <p>三条判定各有用例：对象没了 → 结案；对象终结了但没归档 → 现在归档；
 * 对象还在跑但台账里没有 → 补 watch（**只补 watch 不结案**，它跑得好好的）。
 */
class PipelineRunOrphanSweeperTest {

    private static final Duration GRACE = Duration.ofMinutes(2);
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    private final PlatformPipelineRunMapper runMapper = mock(PlatformPipelineRunMapper.class);
    private final KubernetesOperationsFactory factory = mock(KubernetesOperationsFactory.class);
    private final PipelineRunWatchManager watch = mock(PipelineRunWatchManager.class);
    private final PipelineRunFinisher finisher = mock(PipelineRunFinisher.class);
    private final PipelineArchiveService archive = mock(PipelineArchiveService.class);
    private final PipelineRunOperations ops = mock(PipelineRunOperations.class);

    private PipelineRunOrphanSweeper sweeper() {
        return new PipelineRunOrphanSweeper(runMapper, factory, watch, finisher, archive, GRACE, true);
    }

    /** `getAdminNamespacedOperation` 是 `<T> NamespacedOperations<T>`，泛型推断在 when() 里过不去 —— 用 doReturn。 */
    private void opsAre(PipelineRunOperations runOps) {
        doReturn(runOps).when(factory).getAdminNamespacedOperation(any(ResourceType.class), anyString());
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
        new PipelineRunOrphanSweeper(runMapper, factory, watch, finisher, archive, GRACE, false).sweep(NOW);

        verifyNoInteractions(runMapper, factory, finisher, archive);
    }

    @Test
    void aDatabaseFailureJustSkipsThisSweep() {
        when(runMapper.listRunning()).thenThrow(new RuntimeException("db down"));

        sweeper().sweep(NOW);       // 不抛 —— 下一拍再来

        verifyNoInteractions(factory, finisher, archive);
    }
}
