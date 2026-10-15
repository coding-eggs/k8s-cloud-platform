package com.coding.k8sserver.pipeline;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.pipeline.PipelineRunSnapshotDTO;
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

    /** `getAdminNamespacedOperation` 是 `<T> NamespacedOperations<T>`，泛型推断在 when() 里过不去 —— 用 doReturn。 */
    private void opsAre(PipelineRunOperations runOps) {
        org.mockito.Mockito.doReturn(runOps)
                .when(factory).getAdminNamespacedOperation(any(ResourceType.class), anyString());
    }

    private static PipelineRunSnapshotDTO snapshot(String status) {
        return new PipelineRunSnapshotDTO("c1", "ns1", "r1", status, null, null, null, List.of());
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
        // ⭐ spec §4.2：「取消之后日志仍然看得到」的全部依据。
        //    而且取消**不删对象** —— 历史不能丢，它由后续的终止事件归档。
        opsAre(ops);
        when(ops.snapshot("c1", "ns1", "r1", 1024)).thenReturn(snapshot("RUNNING"));

        finisher.cancel("c1", "ns1", "r1");

        InOrder o = inOrder(ops, archive);
        o.verify(ops).snapshot("c1", "ns1", "r1", 1024);   // 先抓
        o.verify(archive).persist(any());
        o.verify(ops).cancel("ns1", "r1");                 // 再取消
        verify(ops, never()).delete(anyString(), anyString());
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
