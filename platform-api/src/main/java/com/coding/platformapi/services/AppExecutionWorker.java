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
 * <p><b>归属与租约</b>：每拍先 {@code claim} 抢租约并钉住 {@code owner_id}，
 * 抢到才推进，并顺带把租约续到 {@code now + LEASE_MILLIS}；<b>推进一步后不释放租约</b> ——
 * 放开就等于每 3 秒让别的实例来抢一次，两台交替推进同一条执行（曾表现为「发布已中断」、反复点「继续」）。
 * 于是只有本实例停机或死掉后才会被别人接管：
 * <ul>
 *   <li>正常停机（滚动更新 / 缩容，含平台「发布自己」时旧 api Pod 被换掉）：{@link #shutdown} 先停调度、
 *       等手上的步骤跑完，再释放<b>已停下</b>的那些执行的租约，新实例下一拍就接手；</li>
 *   <li>被强杀（OOM / 节点宕机）：来不及释放，等租约自然过期。</li>
 * </ul>
 * 接手方从 {@link ApplicationDeployCredentials}（Redis，多实例共享）取凭据接着跑；取不到才落 INTERRUPTED。
 */
@Slf4j
@Component
public class AppExecutionWorker {

    private static final int QUEUE_CAPACITY = 256;

    private final AppReleaseExecutor executor;
    private final PlatformAppExecutionMapper executions;
    private final AppExecutionLease leases;
    private final ThreadPoolExecutor pool;

    /** 已入队或在跑的执行：慢步骤期间不重复入队，避免同一条占满队列。 */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    /** 停机中：不再入队、不再抢租约（否则刚释放的租约又被自己续上） */
    private volatile boolean stopping;

    /** 停机时等手上步骤跑完的上限（测试里调小） */
    long shutdownWaitMillis = 10_000;

    public AppExecutionWorker(AppReleaseExecutor executor,
                              PlatformAppExecutionMapper executions,
                              AppExecutionLease leases,
                              @Value("${platform.application.worker-threads:4}") int threads) {
        this.executor = executor;
        this.executions = executions;
        this.leases = leases;
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
        if (stopping) {
            return;
        }
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
            if (stopping) {
                return;   // 停机前已入队、还没开始的：不抢，交给接手的实例
            }
            Date now = new Date();
            // 抢租约 + 钉住当拍归属；返回 0 = 另一台实例还持有（凭据在它那儿），这一拍什么都不做
            if (executions.claim(id, leases.instanceId(), now, leases.nextLease(now)) == 0) {
                return;
            }
            // 抢到后重读：列表快照可能已过期，按旧阶段推进会把别的实例刚写的进度倒回去
            PlatformAppExecution fresh = executions.selectById(id);
            if (fresh != null && runnable(fresh)) {
                executor.advance(fresh);
            }
            // 刻意不释放租约（见类注释）：放开就等于每拍把执行让给没有凭据的另一台
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

    /**
     * 停机交接：停调度 → 等手上的步骤跑完（最多 10s）→ 释放本实例已停下的执行的租约。
     * 10s 内没跑完的那几条<b>不释放</b>：放了接手方就会与这一步并发推进，只能等它的租约自然过期。
     * 数据源在本 bean 之后才销毁（本 bean 依赖 mapper），所以这里还能写库。
     */
    @PreDestroy
    public void shutdown() {
        stopping = true;
        pool.shutdown();
        try {
            if (!pool.awaitTermination(shutdownWaitMillis, TimeUnit.MILLISECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        Set<Long> stillRunning = Set.copyOf(inFlight);
        try {
            int released = executions.releaseLeases(leases.instanceId(), stillRunning);
            if (released > 0 || !stillRunning.isEmpty()) {
                log.info("停机交接：释放 {} 条发布执行的租约；仍在跑、未释放：{}", released, stillRunning);
            }
        } catch (RuntimeException e) {
            log.warn("停机交接：释放租约失败，接手方需等租约过期：{}", e.getMessage());
        }
    }
}
