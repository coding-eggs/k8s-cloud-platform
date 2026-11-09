package com.coding.platformapi.services;

import com.coding.data.mapper.system.PlatformAppExecutionLogMapper;
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
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Set;
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
    private static final String ME = "test-instance";

    private AppReleaseExecutor executor;
    private PlatformAppExecutionMapper executions;
    private AppExecutionLease leases;
    private AppExecutionWorker worker;

    @BeforeEach
    void setUp() {
        executor = mock(AppReleaseExecutor.class);
        executions = mock(PlatformAppExecutionMapper.class);
        leases = new AppExecutionLease(ME);
        worker = new AppExecutionWorker(executor, executions, leases, 4);
        when(executions.claim(anyLong(), any(), any(), any())).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        worker.shutdown();
    }

    private static boolean saved(PlatformAppExecutionMapper mapper) {
        return org.mockito.Mockito.mockingDetails(mapper).getInvocations().stream()
                .anyMatch(i -> i.getMethod().getName().equals("updateProgress"));
    }

    private static long claimCount(PlatformAppExecutionMapper mapper) {
        return org.mockito.Mockito.mockingDetails(mapper).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("claim")).count();
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
        release.countDown();
        verify(executor, timeout(WAIT)).advance(a);
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
        verify(executor, timeout(WAIT)).advance(a);

        verify(executor, times(1)).advance(any());
    }

    @Test
    void claimLostSkipsAndKeepsTheOtherOwnersLease() {
        PlatformAppExecution a = stored(1, "RUNNING");
        PlatformAppExecution b = stored(2, "RUNNING");
        when(executions.selectActive()).thenReturn(List.of(a, b));
        when(executions.claim(eq(1L), any(), any(), any())).thenReturn(0);

        worker.tick();

        verify(executor, timeout(WAIT)).advance(b);
        verify(executor, never()).advance(a);
    }

    /**
     * 另一台实例的租约还没过期时，这一拍什么都不做；续租窗口必须明显长于任何一步的最长耗时
     * （HTTP 读超时 120s），否则推进到一半就被人抢走。
     */
    @Test
    void leaseIsThreeMinutesAndStampedWithThisInstance() {
        PlatformAppExecution a = stored(1, "RUNNING");
        when(executions.selectActive()).thenReturn(List.of(a));

        worker.tick();

        ArgumentCaptor<Date> now = ArgumentCaptor.forClass(Date.class);
        ArgumentCaptor<Date> until = ArgumentCaptor.forClass(Date.class);
        verify(executions, timeout(WAIT)).claim(eq(1L), eq(ME), now.capture(), until.capture());
        assertThat(until.getValue().getTime() - now.getValue().getTime())
                .isEqualTo(AppExecutionLease.LEASE_MILLIS);
    }

    @Test
    void interruptedIsWaitingForTheUserAndNeverClaimed() {
        PlatformAppExecution a = stored(1, "INTERRUPTED");
        when(executions.selectActive()).thenReturn(List.of(a));

        worker.tick();
        worker.shutdown();

        verify(executions, never()).claim(anyLong(), any(), any(), any());
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

        verify(executor, never()).advance(any());
    }

    /**
     * 步骤抛异常也不能放开归属：放开租约等于每拍把执行让给没有凭据的另一台实例，
     * 那正是「发布已中断」的成因。下一拍照旧由本实例 claim 续租。
     */
    @Test
    void stepThrowingDoesNotReleaseOwnership() throws Exception {
        PlatformAppExecution a = stored(1, "RUNNING");
        when(executions.selectActive()).thenReturn(List.of(a));
        doThrow(new IllegalStateException("db down")).when(executor).advance(a);

        worker.tick();
        verify(executor, timeout(WAIT)).advance(a);
        for (int i = 0; i < 100 && claimCount(executions) < 2; i++) {
            worker.tick();
            Thread.sleep(20);
        }

        verify(executions, timeout(WAIT).atLeast(2)).claim(eq(1L), eq(ME), any(), any());
    }

    /** 停机交接：手上没在跑的 → 全部释放（不排除任何 id），新实例下一拍就能接手 */
    @Test
    void shutdownReleasesThisInstancesLeases() {
        worker.shutdown();

        verify(executions).releaseLeases(ME, Set.of());
    }

    /** 等待上限内没跑完的那条不能放：放了接手方会与这一步并发推进 */
    @Test
    void shutdownKeepsTheLeaseOfAStepStillRunning() throws Exception {
        PlatformAppExecution a = stored(1, "RUNNING");
        when(executions.selectActive()).thenReturn(List.of(a));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            started.countDown();
            // 不响应中断：模拟卡在一次阻塞调用里
            while (true) {
                try {
                    if (release.await(10, TimeUnit.SECONDS)) {
                        return null;
                    }
                } catch (InterruptedException ignored) {
                    // 继续等
                }
            }
        }).when(executor).advance(a);

        worker.tick();
        assertThat(started.await(WAIT, TimeUnit.MILLISECONDS)).isTrue();
        worker.shutdownWaitMillis = 100;
        worker.shutdown();
        release.countDown();

        verify(executions).releaseLeases(ME, Set.of(1L));
    }

    /** 停机后不再入队、不再抢租约：否则刚释放的租约又被自己续上 */
    @Test
    void nothingIsClaimedAfterShutdown() {
        PlatformAppExecution a = stored(1, "RUNNING");
        when(executions.selectActive()).thenReturn(List.of(a));

        worker.shutdown();
        worker.tick();

        verify(executions, never()).selectActive();
        verify(executions, never()).claim(anyLong(), any(), any(), any());
    }

    @Test
    void releaseFailureDoesNotBreakShutdown() {
        when(executions.releaseLeases(any(), any())).thenThrow(new IllegalStateException("db down"));

        worker.shutdown();   // 不抛

        verify(executions).releaseLeases(eq(ME), any());
    }

    /**
     * Review Focus：实例死后执行停在 RUNNING、凭据随进程消失。
     * 死进程的租约没过期时别的实例抢不到；过期后抢到，因为没有凭据必须落到 INTERRUPTED。
     */
    @Test
    @SuppressWarnings("unchecked")
    void executionLeftRunningByADeadInstanceEndsInterrupted() throws Exception {
        K8sAppClient client = mock(K8sAppClient.class);
        AppReleaseExecutor real = new AppReleaseExecutor(mock(PlatformApplicationMapper.class),
                mock(PlatformAppReleaseMapper.class), executions, mock(PlatformApplicationResourceMapper.class),
                client, mock(AppDependencyService.class), new ApplicationDeployCredentials(),
                new AppExecutionLease("restarted"),
                mock(ObjectProvider.class), mock(ObjectProvider.class),
                new AppExecutionJournal(mock(PlatformAppExecutionLogMapper.class)),
                mock(PlatformTransactionManager.class), 30);
        AppExecutionWorker restarted = new AppExecutionWorker(real, executions, new AppExecutionLease("restarted"), 4);
        try {
            PlatformAppExecution orphan = stored(1, "RUNNING");
            when(executions.selectActive()).thenReturn(List.of(orphan));

            // 死进程的租约还没过期
            when(executions.claim(eq(1L), any(), any(), any())).thenReturn(0);
            restarted.tick();
            verify(executions, timeout(WAIT)).claim(eq(1L), any(), any(), any());
            verify(executions, never()).updateProgress(any(), any(), any(), any(), any());

            // 过期后
            when(executions.claim(eq(1L), any(), any(), any())).thenReturn(1);
            when(executions.updateProgress(any(), any(), any(), any(), any())).thenReturn(1);
            // 上一轮可能还没退出 in-flight，多拍几次才贴近定时任务的真实节奏
            for (int i = 0; i < 100 && !saved(executions); i++) {
                restarted.tick();
                Thread.sleep(20);
            }

            ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> stage = ArgumentCaptor.forClass(String.class);
            verify(executions, timeout(WAIT).atLeastOnce())
                    .updateProgress(eq(1L), status.capture(), stage.capture(), any(), any());
            assertThat(status.getValue()).isEqualTo("INTERRUPTED");
            assertThat(stage.getValue()).isEqualTo("HEALTH");
            verify(client, never()).status(any());
        } finally {
            restarted.shutdown();
        }
    }
}
