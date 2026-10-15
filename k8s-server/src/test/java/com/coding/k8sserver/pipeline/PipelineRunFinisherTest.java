package com.coding.k8sserver.pipeline;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.pipeline.PipelineRunSnapshotDTO;
import com.coding.data.mapper.system.PlatformPipelineRunLogMapper;
import com.coding.data.mapper.system.PlatformPipelineRunMapper;
import com.coding.data.models.system.PlatformPipelineRun;
import com.coding.data.models.system.PlatformPipelineRunLog;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.pipelinerun.PipelineRunOperations;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 终结一次运行要做的事：抓快照 → 落库 → 删对象。
 *
 * <p>⭐ **顺序是它的全部价值**：删 PipelineRun 会级联带走 TaskRun 与 **Pod**，
 * 而 Pod 里那份日志是唯一副本 —— 落库没成功就绝不能删。
 */
class PipelineRunFinisherTest {

    private final KubernetesOperationsFactory factory = mock(KubernetesOperationsFactory.class);
    private final PipelineArchiveService archive = mock(PipelineArchiveService.class);
    private final PipelineRunOperations ops = mock(PipelineRunOperations.class);

    private final PipelineRunFinisher finisher = new PipelineRunFinisher(factory, archive, 1024);

    /**
     * 取消那条路用**真的** {@link PipelineArchiveService}（两个 mapper 是桩）。
     *
     * <p>⚠️ 把 archive mock 掉，就等于把「快照还是 RUNNING 时落库守卫直接拒绝」这件事
     * 一起 mock 掉了 —— 而那正是抢抓日志丢失的藏身之处。取消路径的测试必须走真的落库，
     * 否则「调用发生过」会一直绿着，日志却一个字都没进库。
     */
    private final PlatformPipelineRunMapper runMapper = mock(PlatformPipelineRunMapper.class);
    private final PlatformPipelineRunLogMapper logMapper = mock(PlatformPipelineRunLogMapper.class);
    private final PipelineRunFinisher finisherOverRealArchive =
            new PipelineRunFinisher(factory, new PipelineArchiveService(runMapper, logMapper), 1024);

    /** `getAdminNamespacedOperation` 是 `<T> NamespacedOperations<T>`，泛型推断在 when() 里过不去 —— 用 doReturn。 */
    private void opsAre(PipelineRunOperations runOps) {
        org.mockito.Mockito.doReturn(runOps)
                .when(factory).getAdminNamespacedOperation(any(ResourceType.class), anyString());
    }

    private static PipelineRunSnapshotDTO snapshot(String status, PipelineRunSnapshotDTO.StepLog... steps) {
        return new PipelineRunSnapshotDTO("c1", "ns1", "r1", status, null, null, null,
                List.of(steps), null);
    }

    /** 库里那条运行行（取消路径要求它是 RUNNING —— 运行确实还在跑）。 */
    private static PlatformPipelineRun runningRow() {
        PlatformPipelineRun row = new PlatformPipelineRun();
        row.setId("run-1");
        row.setRunName("r1");
        row.setStatus("RUNNING");
        return row;
    }

    @Test
    void itCapturesPersistsThenDeletesInThatOrder() {
        opsAre(ops);
        when(ops.snapshot("c1", "ns1", "r1", 1024)).thenReturn(snapshot("SUCCEEDED"));

        assertThat(finisher.finish("c1", "ns1", "r1", false)).isTrue();

        InOrder o = inOrder(ops, archive);
        o.verify(ops).snapshot("c1", "ns1", "r1", 1024);
        o.verify(archive).persist(any());
        o.verify(ops).delete("ns1", "r1");
    }

    @Test
    void aFailedPersistLeavesTheObjectAloneSoTheLogsSurvive() {
        // ⭐ 落库失败还去删对象 = 把那份日志的唯一副本删了。宁可多留一会儿，等重投
        opsAre(ops);
        when(ops.snapshot("c1", "ns1", "r1", 1024)).thenReturn(snapshot("FAILED"));
        doThrow(new RuntimeException("db down")).when(archive).persist(any());

        assertThat(finisher.finish("c1", "ns1", "r1", false)).isFalse();

        verify(ops, never()).delete(anyString(), anyString());
    }

    @Test
    void aFailedCaptureAlsoLeavesTheObjectAlone() {
        opsAre(ops);
        when(ops.snapshot(anyString(), anyString(), anyString(), anyInt()))
                .thenThrow(new CloudPlatformException(5000, "cluster down"));

        assertThat(finisher.finish("c1", "ns1", "r1", false)).isFalse();

        verify(ops, never()).delete(anyString(), anyString());
    }

    @Test
    void aTimeoutRewritesTheSnapshotStatusBecauseTektonStillSaysRunning() {
        // 平台判超时时 Tekton 那边**还在跑**（平台是"先抓日志、再取消"）——
        // 光看对象的 condition 分不出「用户取消」与「平台判超时」，运行历史里两者必须不一样
        opsAre(ops);
        when(ops.snapshot("c1", "ns1", "r1", 1024)).thenReturn(snapshot("RUNNING"));

        finisher.finish("c1", "ns1", "r1", true);

        ArgumentCaptor<PipelineRunSnapshotDTO> cap = ArgumentCaptor.forClass(PipelineRunSnapshotDTO.class);
        verify(archive).persist(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo("TIMEOUT");
    }

    @Test
    void anObjectThatIsAlreadyGoneIsStillDeletedIdempotently() {
        opsAre(ops);
        when(ops.snapshot("c1", "ns1", "r1", 1024)).thenReturn(null);

        assertThat(finisher.finish("c1", "ns1", "r1", false)).isTrue();

        verify(archive, never()).persist(any());
        verify(ops).delete("ns1", "r1");
    }

    @Test
    void cancelCapturesBeforeCancellingAndNeverDeletes() {
        // ⭐ spec §4.2：日志必须在**取消之前**就进库 —— Tekton 一取消就把 Pod 删了，
        //    顺序反了那份日志就没了。所以这里盯着真实效果（insert）与 cancel 的先后，
        //    而不是「archive 被调用过」。
        //    而且取消**不删对象** —— 历史不能丢，它由后续的终止事件归档。
        opsAre(ops);
        when(runMapper.selectByRunName("r1")).thenReturn(runningRow());
        when(ops.snapshot("c1", "ns1", "r1", 1024)).thenReturn(snapshot("RUNNING",
                new PipelineRunSnapshotDTO.StepLog("build", "RUNNING", "编译到一半的输出", false, 24)));

        finisherOverRealArchive.cancel("c1", "ns1", "r1");

        InOrder o = inOrder(ops, logMapper);
        o.verify(ops).snapshot("c1", "ns1", "r1", 1024);   // 先抓
        o.verify(logMapper).insert(any());                 // 抓到就先落库
        o.verify(ops).cancel("ns1", "r1");                 // 再取消（这一步会带走 Pod）
        verify(ops, never()).delete(anyString(), anyString());
    }

    @Test
    void theLogsGrabbedBeforeCancelReallyLandInTheDatabase() {
        // ⭐ spec §4.2「取消之后日志仍然看得到」的**实际**依据。
        //    取消的那一刻对象**还在跑**，所以快照状态就是 RUNNING —— 抢抓的日志必须照样落库，
        //    但那条运行**不能**被结案：它还没终结，结案归后续的终止事件。
        opsAre(ops);
        PlatformPipelineRun row = runningRow();
        when(runMapper.selectByRunName("r1")).thenReturn(row);
        when(ops.snapshot("c1", "ns1", "r1", 1024)).thenReturn(snapshot("RUNNING",
                new PipelineRunSnapshotDTO.StepLog("build", "RUNNING", "编译到一半的输出", false, 24)));

        finisherOverRealArchive.cancel("c1", "ns1", "r1");

        ArgumentCaptor<PlatformPipelineRunLog> line = ArgumentCaptor.forClass(PlatformPipelineRunLog.class);
        verify(logMapper).insert(line.capture());
        assertThat(line.getValue().getRunId()).isEqualTo("run-1");
        assertThat(line.getValue().getNodeName()).isEqualTo("build");
        assertThat(line.getValue().getContent()).isEqualTo("编译到一半的输出");

        verify(runMapper, never()).update(any());          // 不结案
        assertThat(row.getStatus()).isEqualTo("RUNNING");  // 那行还是「跑着」
        verify(ops, never()).delete(anyString(), anyString());
        verify(ops).cancel("ns1", "r1");
    }

    @Test
    void aFailedCaptureDoesNotBlockTheCancelTheUserAskedFor() {
        // 用户点的是「取消」，不能因为抢抓失败而卡住（代价是日志可能不全，spec §4.2 已写明）
        opsAre(ops);
        when(ops.snapshot(anyString(), anyString(), anyString(), anyInt()))
                .thenThrow(new CloudPlatformException(5000, "cluster down"));

        finisher.cancel("c1", "ns1", "r1");

        verify(ops).cancel("ns1", "r1");
    }
}
