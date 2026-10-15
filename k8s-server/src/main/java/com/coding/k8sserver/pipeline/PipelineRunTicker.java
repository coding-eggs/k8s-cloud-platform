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
 *   <li><b>超时判定</b> —— 看台账里有没有跑太久的，有就交给 {@link PipelineRunFinisher#finish}
 *       按超时终结（**先抓日志、再删对象** —— 见 {@link #terminateAsTimeout}）</li>
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
            terminateAsTimeout(run);
        }
        retryPending();
    }

    /**
     * 平台判定超时 → 交给 {@code PipelineRunFinisher.finish(..., timedOut = true)} 终结。
     *
     * <p>⚠️ <b>它和「用户取消」不是同一条路</b>，别看混了：
     * <ul>
     *   <li><b>取消</b>（{@code PipelineRunFinisher.cancel}）：抓日志 → <b>patch {@code spec.status}</b>，
     *       对象留着（历史不能丢），由后续的终止事件归档</li>
     *   <li><b>超时</b>（这里）：抓日志 → 状态改写成 {@code TIMEOUT} → 落库 → <b>删对象</b></li>
     * </ul>
     * 两条路**共有的只有那个顺序**：抢在 Tekton 把 Pod 删掉之前先抓一份日志。
     * 超时这条也留得住日志，是因为删对象排在落库**之后**（删 PipelineRun 会级联带走 TaskRun 与 Pod）。
     *
     * <p>顺序本身在 k8s-server 的 {@code PipelineRunFinisher} 里 —— 不在 platform-api，那里已经不在归档链上了。
     *
     * <p>这里只负责**把「这是超时」这件事说出去**（{@code watch.onTerminatedAsTimeout}）。
     */
    void terminateAsTimeout(PipelineRunWatchManager.ActiveRun run) {
        log.warn("流水线运行超过 {} 未结束，交由平台按超时终结：{}/{}",
                timeout, run.namespace(), run.runName());
        watch.onTerminatedAsTimeout(run.clusterId(), run.namespace(), run.runName());
    }

    void retryPending() {
        watch.retryPending();
    }
}
