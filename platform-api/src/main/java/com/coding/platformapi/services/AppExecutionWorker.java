package com.coding.platformapi.services;

import com.coding.data.mapper.system.PlatformAppExecutionMapper;
import com.coding.data.models.system.PlatformAppExecution;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 发布执行的调度：每拍取所有活动执行，交给有界线程池各推进一步。
 *
 * <p>定时线程只做一次查询和入队，集群调用都在池里，一个应用慢不会拖住其他应用。
 *
 * <p>INTERRUPTED 在等用户点「继续」，不参与调度。
 *
 * <p>平台重启后：RUNNING 行还在、凭据已没了。死进程留下的租约过期后这里能抢到，
 * executor 发现没有凭据就落 INTERRUPTED，不会永远停在 RUNNING。
 */
@Slf4j
@Component
public class AppExecutionWorker {

    private static final long LEASE_MILLIS = 2 * 60 * 1000L;
    private static final int QUEUE_CAPACITY = 256;

    private final AppReleaseExecutor executor;
    private final PlatformAppExecutionMapper executions;
    private final ThreadPoolExecutor pool;

    /** 已入队或在跑的执行：慢步骤期间不重复入队，避免同一条占满队列。 */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public AppExecutionWorker(AppReleaseExecutor executor,
                              PlatformAppExecutionMapper executions,
                              @Value("${platform.application.worker-threads:4}") int threads) {
        this.executor = executor;
        this.executions = executions;
        AtomicInteger seq = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
                    Thread thread = new Thread(runnable, "app-release-" + seq.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
    }

    @Scheduled(fixedDelayString = "${platform.application.worker-delay-ms:3000}")
    public void tick() {
        for (PlatformAppExecution execution : executions.selectActive()) {
            if (!runnable(execution)) {
                continue;
            }
            Long id = execution.getId();
            if (!inFlight.add(id)) {
                continue;
            }
            try {
                pool.execute(() -> run(id));
            } catch (RejectedExecutionException e) {
                inFlight.remove(id);   // 队列满或正在停机：下一拍再试
            }
        }
    }

    private void run(Long id) {
        try {
            Date now = new Date();
            if (executions.claim(id, now, new Date(now.getTime() + LEASE_MILLIS)) == 0) {
                return;
            }
            try {
                // 抢到后重读：列表快照可能已过期，按旧阶段推进会把别的副本刚写的进度倒回去
                PlatformAppExecution fresh = executions.selectById(id);
                if (fresh != null && runnable(fresh)) {
                    executor.advance(fresh);
                }
            } finally {
                executions.releaseLease(id);
            }
        } catch (RuntimeException e) {
            log.error("推进发布执行 {} 失败，下一拍重试", id, e);
        } finally {
            inFlight.remove(id);
        }
    }

    private static boolean runnable(PlatformAppExecution execution) {
        return AppReleaseExecutor.PENDING.equals(execution.getStatus())
                || AppReleaseExecutor.RUNNING.equals(execution.getStatus());
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
