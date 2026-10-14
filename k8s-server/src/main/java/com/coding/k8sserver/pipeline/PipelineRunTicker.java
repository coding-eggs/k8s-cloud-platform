package com.coding.k8sserver.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 节拍器。每一拍干两件事：
 * <ol>
 *   <li><b>超时判定</b> —— 看台账里有没有跑太久的，有就让平台去取消（先抓日志再取消，见 platform-api 侧）</li>
 *   <li><b>重投待补</b> —— 上一拍回调没被确认的</li>
 * </ol>
 *
 * <p>⚠️ <b>它只看台账、不打集群</b>，而且台账空时它是空转 ——
 * 这是它不算「轮询集群」的全部理由（spec §4.2）。
 *
 * <p>设计成 {@code tick(Instant)} 是纯逻辑、{@code @Scheduled} 只是调用者，
 * 是为了让「每一拍该做什么决定」可单测。
 */
@Slf4j
@Component
public class PipelineRunTicker {

    private final PipelineRunWatchManager watch;
    private final Duration timeout;
    private final Boolean enabled;

    public PipelineRunTicker(PipelineRunWatchManager watch,
                             @Value("${platform.pipeline.run.timeout:30m}") Duration timeout,
                             @Value("${platform.pipeline.archive.enabled:true}") Boolean enabled) {
        this.watch = watch;
        this.timeout = timeout;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${platform.pipeline.run.timeout-check:30s}")
    public void scheduledTick() {
        if (!Boolean.TRUE.equals(enabled)) {
            return;
        }
        tick(Instant.now());
    }

    /** 可单测的入口。 */
    void tick(Instant now) {
        for (PipelineRunWatchManager.ActiveRun run : watch.overdue(now, timeout)) {
            cancelOverdue(run);
        }
        retryPending();
    }

    /**
     * 平台判定超时 → 走与取消同一条路（**先抓日志、再取消**）。
     * 真正的顺序在 platform-api 侧；这里只负责**把「这是超时」这件事说出去**。
     */
    void cancelOverdue(PipelineRunWatchManager.ActiveRun run) {
        log.warn("流水线运行超过 {} 未结束，交由平台按超时终结：{}/{}",
                timeout, run.namespace(), run.runName());
        watch.onTerminatedAsTimeout(run.clusterId(), run.namespace(), run.runName());
    }

    void retryPending() {
        watch.retryPending();
    }
}
