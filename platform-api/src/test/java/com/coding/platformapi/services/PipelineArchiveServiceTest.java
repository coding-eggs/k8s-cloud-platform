package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.dto.PipelineRunDTO;
import com.coding.common.models.pipeline.PipelineStepRefDTO;
import com.coding.data.mapper.system.PlatformPipelineRunLogMapper;
import com.coding.data.mapper.system.PlatformPipelineRunMapper;
import com.coding.data.models.system.PlatformPipelineRun;
import com.coding.data.models.system.PlatformPipelineRunLog;
import com.coding.platformapi.k8s.K8sPipelineClient;
import com.coding.platformapi.models.PipelineRunContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 归档：抓快照 → 落库 → 删对象（spec §4.5③）。**顺序就是这一条链的全部价值**，所以用 InOrder 钉死。
 */
class PipelineArchiveServiceTest {

    private static final int MAX = 1_048_576;

    private final PlatformPipelineRunMapper runMapper = mock(PlatformPipelineRunMapper.class);
    private final PlatformPipelineRunLogMapper logMapper = mock(PlatformPipelineRunLogMapper.class);
    private final K8sPipelineClient k8s = mock(K8sPipelineClient.class);

    private final PipelineArchiveService svc =
            new PipelineArchiveService(runMapper, logMapper, k8s, MAX);

    private static PlatformPipelineRun row(String status) {
        PlatformPipelineRun r = new PlatformPipelineRun();
        r.setId("run-1");
        r.setRunName("r1");
        r.setPipelineId("p1");
        r.setClusterId("c1");
        r.setNamespace("ns1");
        r.setStatus(status);
        return r;
    }

    private static PipelineRunDTO snapshot(String status) {
        PipelineRunDTO d = new PipelineRunDTO();
        d.setName("r1");
        d.setStatus(status);
        d.setStartTime("2026-10-15T10:00:00Z");
        d.setCompletionTime("2026-10-15T10:03:00Z");
        return d;
    }

    private void oneStepWithLog(String log) {
        when(k8s.steps("c1", null, "ns1", "r1")).thenReturn(
                List.of(new PipelineStepRefDTO("clone", "SUCCEEDED", "clone-pod", List.of("step-run"))));
        when(k8s.podLog(anyString(), any(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new K8sPipelineClient.CappedLog(log, false, log.length()));
    }

    @Test
    void aTerminatedRunIsCapturedThenPersistedThenDeletedInThatOrder() {
        when(runMapper.selectByRunName("r1")).thenReturn(row("RUNNING"));
        when(k8s.getRun("c1", null, "ns1", "r1")).thenReturn(snapshot("SUCCEEDED"));
        oneStepWithLog("hello");

        svc.archive("c1", "ns1", "r1");

        var o = inOrder(k8s, logMapper, runMapper);
        o.verify(k8s).getRun("c1", null, "ns1", "r1");    // 抓状态
        o.verify(k8s).steps("c1", null, "ns1", "r1");     // 抓落点
        o.verify(logMapper, atLeastOnce()).insert(any()); // 落日志
        o.verify(runMapper).update(any());                // 改状态
        o.verify(k8s).deleteRun("c1", null, "ns1", "r1"); // **最后**删
    }

    @Test
    void anAlreadyArchivedRunStillTriesTheDelete() {
        // ⭐ Review Focus #2 的正面：幂等分支**不能直接 return** ——
        // 想象「库写好了但删对象失败」，直接 return 会让对象永远留在集群里，
        // 而那正是本批要消掉的东西。
        when(runMapper.selectByRunName("r1")).thenReturn(row("SUCCEEDED"));

        svc.archive("c1", "ns1", "r1");

        verify(runMapper, never()).update(any());          // 不重写库
        verify(logMapper, never()).insert(any());          // 也不重抓日志
        verify(k8s).deleteRun("c1", null, "ns1", "r1");    // **但删除照做**
    }

    @Test
    void aRunningRunIsRefusedNotArchivedAsFinished() {
        // 不能把 Running 的当完成的
        when(runMapper.selectByRunName("r1")).thenReturn(row("RUNNING"));
        when(k8s.getRun("c1", null, "ns1", "r1")).thenReturn(snapshot("RUNNING"));

        assertThatThrownBy(() -> svc.archive("c1", "ns1", "r1"))
                .isInstanceOf(CloudPlatformException.class);

        verify(runMapper, never()).update(any());
        verify(k8s, never()).deleteRun(anyString(), any(), anyString(), anyString());
    }

    @Test
    void aTimeoutCallbackIsAcceptedEvenThoughTektonStillSaysRunning() {
        // ⭐ Review Focus：平台判超时时，Tekton 那边**还在跑**（平台是"先抓日志、再取消"）。
        // 这条回调恰恰是平台自己的终结决定，所以不能被"未终结不归档"那道闸拦下。
        when(runMapper.selectByRunName("r1")).thenReturn(row("RUNNING"));
        when(k8s.getRun("c1", null, "ns1", "r1")).thenReturn(snapshot("RUNNING"));
        oneStepWithLog("partial");

        svc.archive("c1", "ns1", "r1", true);

        ArgumentCaptor<PlatformPipelineRun> cap = ArgumentCaptor.forClass(PlatformPipelineRun.class);
        verify(runMapper).update(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("TIMEOUT");
        verify(k8s).deleteRun("c1", null, "ns1", "r1");
    }

    @Test
    void aLogOverTheLimitIsStoredTruncatedAndFlagged() {
        when(runMapper.selectByRunName("r1")).thenReturn(row("RUNNING"));
        when(k8s.getRun("c1", null, "ns1", "r1")).thenReturn(snapshot("SUCCEEDED"));
        when(k8s.steps("c1", null, "ns1", "r1")).thenReturn(
                List.of(new PipelineStepRefDTO("build", "SUCCEEDED", "b-pod", List.of("step-run"))));
        when(k8s.podLog(anyString(), any(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new K8sPipelineClient.CappedLog("x".repeat(100), true, 2_000_000));

        svc.archive("c1", "ns1", "r1");

        ArgumentCaptor<PlatformPipelineRunLog> cap = ArgumentCaptor.forClass(PlatformPipelineRunLog.class);
        verify(logMapper).insert(cap.capture());
        assertThat(cap.getValue().getTruncated()).isEqualTo(1);
        assertThat(cap.getValue().getSizeBytes()).isEqualTo(2_000_000);
    }

    @Test
    void aLogThatVanishesMidwayIsStillStoredAsPartial() {
        // 竞态：抓到一半 Pod 没了 —— 抓到多少存多少，**不整条丢**
        when(runMapper.selectByRunName("r1")).thenReturn(row("RUNNING"));
        when(k8s.getRun("c1", null, "ns1", "r1")).thenReturn(snapshot("FAILED"));
        when(k8s.steps("c1", null, "ns1", "r1")).thenReturn(
                List.of(new PipelineStepRefDTO("build", "FAILED", "b-pod", List.of("step-run"))));
        when(k8s.podLog(anyString(), any(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new K8sPipelineClient.CappedLog("part", false, 4));

        svc.archive("c1", "ns1", "r1");

        ArgumentCaptor<PlatformPipelineRunLog> cap = ArgumentCaptor.forClass(PlatformPipelineRunLog.class);
        verify(logMapper).insert(cap.capture());
        assertThat(cap.getValue().getContent()).isEqualTo("part");
        verify(runMapper).update(any());
    }

    @Test
    void aStepThatNeverStartedHasNoPodSoNoLogIsFetched() {
        when(runMapper.selectByRunName("r1")).thenReturn(row("RUNNING"));
        when(k8s.getRun("c1", null, "ns1", "r1")).thenReturn(snapshot("FAILED"));
        when(k8s.steps("c1", null, "ns1", "r1")).thenReturn(
                List.of(new PipelineStepRefDTO("build", "RUNNING", null, List.of())));

        svc.archive("c1", "ns1", "r1");

        verify(k8s, never()).podLog(anyString(), any(), anyString(), anyString(), anyString(), anyInt());
        verify(runMapper).update(any());       // 状态与快照照写
    }

    @Test
    void captureGrabsLogsWithoutTouchingTheObject() {
        // 取消路径用：**先抓日志**，取消由调用方紧接着做（顺序是"取消后日志还在"的全部依据）。
        // 走的是**发起取消的那个人**的身份（tenantId 来自 ctx），不是平台身份。
        when(runMapper.selectByRunName("r1")).thenReturn(row("RUNNING"));
        when(k8s.steps("c1", "t1", "ns1", "r1")).thenReturn(
                List.of(new PipelineStepRefDTO("clone", "RUNNING", "clone-pod", List.of("step-run"))));
        when(k8s.podLog(anyString(), any(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new K8sPipelineClient.CappedLog("live", false, 4));

        svc.capture("r1", new PipelineRunContext("c1", "ns1", "t1"));

        verify(logMapper, atLeastOnce()).insert(any());
        verify(k8s, never()).deleteRun(anyString(), any(), anyString(), anyString());
    }

    @Test
    void anObjectThatIsAlreadyGoneIsNotAnError() {
        // 归档重投时对象可能已经被删掉了 —— 那不是失败
        when(runMapper.selectByRunName("r1")).thenReturn(row("RUNNING"));
        when(k8s.getRun("c1", null, "ns1", "r1")).thenReturn(null);

        svc.archive("c1", "ns1", "r1");

        verify(runMapper, never()).update(any());
        verify(k8s).deleteRun("c1", null, "ns1", "r1");   // 幂等：再删一次也无妨
    }
}
