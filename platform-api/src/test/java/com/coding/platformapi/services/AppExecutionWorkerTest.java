package com.coding.platformapi.services;

import com.coding.data.mapper.system.PlatformAppExecutionMapper;
import com.coding.data.mapper.system.PlatformAppReleaseMapper;
import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.mapper.system.PlatformApplicationResourceMapper;
import com.coding.data.models.system.PlatformAppExecution;
import com.coding.platformapi.k8s.K8sAppClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppExecutionWorkerTest {

    private static final long WAIT = 3000;

    private AppReleaseExecutor executor;
    private PlatformAppExecutionMapper executions;
    private AppExecutionWorker worker;

    @BeforeEach
    void setUp() {
        executor = mock(AppReleaseExecutor.class);
        executions = mock(PlatformAppExecutionMapper.class);
        worker = new AppExecutionWorker(executor, executions, 4);
        when(executions.claim(anyLong(), any(), any())).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        worker.shutdown();
    }

    private static boolean saved(PlatformAppExecutionMapper mapper) {
        return org.mockito.Mockito.mockingDetails(mapper).getInvocations().stream()
                .anyMatch(i -> i.getMethod().getName().equals("updateById"));
    }

    private PlatformAppExecution stored(long id, String status) {
        PlatformAppExecution e = new PlatformAppExecution();
        e.setId(id);
        e.setAppId("app_" + id);
        e.setReleaseNo(1);
        e.setStatus(status);
        e.setStage("HEALTH");
        when(executions.selectById(id)).thenReturn(e);
        return e;
    }

    @Test
    void appStuckInACallDoesNotBlockOtherAppOrTheSchedulerThread() throws Exception {
        PlatformAppExecution a = stored(1, "RUNNING");
        PlatformAppExecution b = stored(2, "RUNNING");
        when(executions.selectActive()).thenReturn(List.of(a, b));
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(executor).advance(a);

        long start = System.nanoTime();
        worker.tick();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));

        verify(executor, timeout(WAIT)).advance(b);
        verify(executions, timeout(WAIT)).releaseLease(2L);
        verify(executions, never()).releaseLease(1L);
        release.countDown();
        verify(executions, timeout(WAIT)).releaseLease(1L);
    }

    @Test
    void executionStillInFlightIsNotQueuedAgain() throws Exception {
        PlatformAppExecution a = stored(1, "RUNNING");
        when(executions.selectActive()).thenReturn(List.of(a));
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(executor).advance(a);

        worker.tick();
        verify(executor, timeout(WAIT)).advance(a);
        worker.tick();
        worker.tick();
        release.countDown();
        verify(executions, timeout(WAIT)).releaseLease(1L);

        verify(executor, times(1)).advance(any());
    }

    @Test
    void claimLostSkipsAndKeepsTheOtherOwnersLease() throws Exception {
        PlatformAppExecution a = stored(1, "RUNNING");
        PlatformAppExecution b = stored(2, "RUNNING");
        when(executions.selectActive()).thenReturn(List.of(a, b));
        when(executions.claim(eq(1L), any(), any())).thenReturn(0);

        worker.tick();

        verify(executor, timeout(WAIT)).advance(b);
        verify(executions, timeout(WAIT)).releaseLease(2L);
        verify(executor, never()).advance(a);
        verify(executions, never()).releaseLease(1L);
    }

    @Test
    void leaseIsTwoMinutes() {
        PlatformAppExecution a = stored(1, "RUNNING");
        when(executions.selectActive()).thenReturn(List.of(a));

        worker.tick();

        ArgumentCaptor<Date> now = ArgumentCaptor.forClass(Date.class);
        ArgumentCaptor<Date> until = ArgumentCaptor.forClass(Date.class);
        verify(executions, timeout(WAIT)).claim(eq(1L), now.capture(), until.capture());
        assertThat(until.getValue().getTime() - now.getValue().getTime()).isEqualTo(120_000L);
    }

    @Test
    void interruptedIsWaitingForTheUserAndNeverClaimed() {
        PlatformAppExecution a = stored(1, "INTERRUPTED");
        when(executions.selectActive()).thenReturn(List.of(a));

        worker.tick();
        worker.shutdown();

        verify(executions, never()).claim(anyLong(), any(), any());
        verify(executor, never()).advance(any());
    }

    /** selectActive 的快照可能已过期：别的副本刚走完一步。必须按抢到后重读的那份推进。 */
    @Test
    void advancesTheRowReadAfterClaimNotTheStaleSnapshot() {
        PlatformAppExecution snapshot = stored(1, "RUNNING");
        PlatformAppExecution fresh = new PlatformAppExecution();
        fresh.setId(1L);
        fresh.setStatus("RUNNING");
        fresh.setStage("PRUNE");
        when(executions.selectActive()).thenReturn(List.of(snapshot));
        when(executions.selectById(1L)).thenReturn(fresh);

        worker.tick();

        ArgumentCaptor<PlatformAppExecution> c = ArgumentCaptor.forClass(PlatformAppExecution.class);
        verify(executor, timeout(WAIT)).advance(c.capture());
        assertThat(c.getValue().getStage()).isEqualTo("PRUNE");
    }

    @Test
    void rowThatFinishedBetweenSnapshotAndClaimIsNotAdvanced() {
        PlatformAppExecution snapshot = stored(1, "RUNNING");
        PlatformAppExecution fresh = new PlatformAppExecution();
        fresh.setId(1L);
        fresh.setStatus("SUCCESS");
        when(executions.selectActive()).thenReturn(List.of(snapshot));
        when(executions.selectById(1L)).thenReturn(fresh);

        worker.tick();

        verify(executions, timeout(WAIT)).releaseLease(1L);
        verify(executor, never()).advance(any());
    }

    @Test
    void leaseIsClearedEvenWhenTheStepThrows() {
        PlatformAppExecution a = stored(1, "RUNNING");
        when(executions.selectActive()).thenReturn(List.of(a));
        doThrow(new IllegalStateException("db down")).when(executor).advance(a);

        worker.tick();
        verify(executions, timeout(WAIT)).releaseLease(1L);

        verify(executor, timeout(WAIT)).advance(a);
    }

    /**
     * Review Focus：platform-api 重启后，RUNNING 行还在库里、凭据已没了。
     * 用真 executor + 真凭据：死进程留下的租约未过期时跳过，过期后必须落到 INTERRUPTED。
     */
    @Test
    @SuppressWarnings("unchecked")
    void executionLeftRunningByARestartEndsInterrupted() throws Exception {
        K8sAppClient client = mock(K8sAppClient.class);
        AppReleaseExecutor real = new AppReleaseExecutor(mock(PlatformApplicationMapper.class),
                mock(PlatformAppReleaseMapper.class), executions, mock(PlatformApplicationResourceMapper.class),
                client, new ApplicationDeployCredentials(),
                mock(ObjectProvider.class), mock(ObjectProvider.class), 30);
        AppExecutionWorker restarted = new AppExecutionWorker(real, executions, 4);
        try {
            PlatformAppExecution orphan = stored(1, "RUNNING");
            when(executions.selectActive()).thenReturn(List.of(orphan));

            // 死进程的租约还没过期
            when(executions.claim(eq(1L), any(), any())).thenReturn(0);
            restarted.tick();
            verify(executions, timeout(WAIT)).claim(eq(1L), any(), any());
            verify(executions, never()).updateById(any());

            // 过期后
            when(executions.claim(eq(1L), any(), any())).thenReturn(1);
            // 上一轮可能还没退出 in-flight，多拍几次才贴近定时任务的真实节奏
            for (int i = 0; i < 100 && !saved(executions); i++) {
                restarted.tick();
                Thread.sleep(20);
            }

            ArgumentCaptor<PlatformAppExecution> saved = ArgumentCaptor.forClass(PlatformAppExecution.class);
            verify(executions, timeout(WAIT).atLeastOnce()).updateById(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo("INTERRUPTED");
            assertThat(saved.getValue().getStage()).isEqualTo("HEALTH");
            verify(executions, timeout(WAIT).atLeastOnce()).releaseLease(1L);
            verify(client, never()).status(any());
        } finally {
            restarted.shutdown();
        }
    }
}
