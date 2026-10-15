package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.pipeline.PipelineRunSnapshotDTO;
import com.coding.data.mapper.system.PlatformPipelineRunLogMapper;
import com.coding.data.mapper.system.PlatformPipelineRunMapper;
import com.coding.data.models.system.PlatformPipelineRun;
import com.coding.data.models.system.PlatformPipelineRunLog;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 归档的**落库那一半**。
 *
 * <p>⚠️ 这里**没有任何集群调用**：快照由 k8s-server 抓（watch 是它开的），随回调送过来。
 * 所以本类的桩只有两个 mapper —— 这也正是「platform-api 在归档链里不需要机器身份」的样子。
 */
class PipelineArchiveServiceTest {

    private final PlatformPipelineRunMapper runMapper = mock(PlatformPipelineRunMapper.class);
    private final PlatformPipelineRunLogMapper logMapper = mock(PlatformPipelineRunLogMapper.class);

    private final PipelineArchiveService svc = new PipelineArchiveService(runMapper, logMapper);

    private static PlatformPipelineRun running() {
        PlatformPipelineRun r = new PlatformPipelineRun();
        r.setId("run-1");
        r.setRunName("r1");
        r.setPipelineId("p1");
        r.setClusterId("c1");
        r.setNamespace("ns1");
        r.setStatus("RUNNING");
        return r;
    }

    private static PipelineRunSnapshotDTO snapshot(String status, PipelineRunSnapshotDTO.StepLog... steps) {
        return new PipelineRunSnapshotDTO("c1", "ns1", "r1", status, "reason-x",
                "2026-10-15T10:00:00Z", "2026-10-15T10:03:00Z", List.of(steps));
    }

    private static PipelineRunSnapshotDTO.StepLog step(String node, String log, boolean truncated, int size) {
        return new PipelineRunSnapshotDTO.StepLog(node, "SUCCEEDED", log, truncated, size);
    }

    @Test
    void aTerminatedSnapshotWritesTheLogsThenTheRunStatus() {
        when(runMapper.selectByRunName("r1")).thenReturn(running());

        svc.persist(snapshot("SUCCEEDED", step("clone", "hello", false, 5), step("build", "world", false, 5)));

        var o = inOrder(logMapper, runMapper);
        o.verify(logMapper, org.mockito.Mockito.atLeastOnce()).insert(any());
        o.verify(runMapper).update(any());

        ArgumentCaptor<PlatformPipelineRun> cap = ArgumentCaptor.forClass(PlatformPipelineRun.class);
        verify(runMapper).update(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("SUCCEEDED");
        assertThat(cap.getValue().getStepsJson()).isEqualTo("clone=SUCCEEDED,build=SUCCEEDED");
        assertThat(cap.getValue().getCompletionTime()).isEqualTo("2026-10-15T10:03:00Z");
    }

    @Test
    void anAlreadyArchivedRunIsNotRewritten() {
        // ⭐ 幂等：重投只写一次。**而且这里返回正常**（不抛）——
        // 调用方据此回 200，k8s-server 才会去删对象；抛了就变成「库写好了但对象永远删不掉」。
        PlatformPipelineRun done = running();
        done.setStatus("SUCCEEDED");
        when(runMapper.selectByRunName("r1")).thenReturn(done);

        svc.persist(snapshot("SUCCEEDED", step("clone", "hello", false, 5)));

        verify(runMapper, never()).update(any());
        verify(logMapper, never()).insert(any());
    }

    @Test
    void aRunThatIsNotOursIsSkippedNotInvented() {
        when(runMapper.selectByRunName("r1")).thenReturn(null);

        svc.persist(snapshot("SUCCEEDED", step("clone", "hello", false, 5)));

        verify(runMapper, never()).update(any());
        verify(logMapper, never()).insert(any());
    }

    @Test
    void aSnapshotThatStillSaysRunningIsRefusedSoItIsNotAcked() {
        // 防御：真到了这里说明对方的判断有误 —— **不 ack**，让它留着对象与 Pod（那份日志还在），
        // 比删掉之后再想找回日志要好
        when(runMapper.selectByRunName("r1")).thenReturn(running());

        assertThatThrownBy(() -> svc.persist(snapshot("RUNNING", step("clone", "x", false, 1))))
                .isInstanceOf(CloudPlatformException.class);

        verify(runMapper, never()).update(any());
    }

    @Test
    void truncationIsCarriedThroughFromTheSnapshot() {
        // 截断是 k8s-server 在 API 侧做的，平台只负责照实记下来
        when(runMapper.selectByRunName("r1")).thenReturn(running());

        svc.persist(snapshot("SUCCEEDED", step("build", "x", true, 2_000_000)));

        ArgumentCaptor<PlatformPipelineRunLog> cap = ArgumentCaptor.forClass(PlatformPipelineRunLog.class);
        verify(logMapper).insert(cap.capture());
        assertThat(cap.getValue().getTruncated()).isEqualTo(1);
        assertThat(cap.getValue().getSizeBytes()).isEqualTo(2_000_000);
    }

    @Test
    void aNodeWithNoLogWritesNoRow() {
        // 「没有日志」与「日志是空的」不是一回事
        when(runMapper.selectByRunName("r1")).thenReturn(running());

        svc.persist(snapshot("FAILED", step("build", "", false, 0)));

        verify(logMapper, never()).insert(any());
        verify(runMapper).update(any());     // 状态与快照照写
    }
}
