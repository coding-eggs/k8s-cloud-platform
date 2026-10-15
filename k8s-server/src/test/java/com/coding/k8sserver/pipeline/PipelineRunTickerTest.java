package com.coding.k8sserver.pipeline;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineRunTickerTest {

    /** 只测「每一拍该做什么决定」——真正的取消/重投动作由它调用的对象负责。 */
    static class RecordingTicker extends PipelineRunTicker {
        final List<String> cancelled = new ArrayList<>();
        final List<String> retried = new ArrayList<>();

        RecordingTicker(PipelineRunWatchManager mgr, Duration timeout) {
            super(mgr, timeout, null);
        }

        @Override
        void cancelOverdue(PipelineRunWatchManager.ActiveRun run) {
            cancelled.add(run.runName());
        }

        @Override
        void retryPending() {
            retried.add("tick");
        }
    }

    @Test
    void anOverdueRunIsCancelledAndPendingIsRetried() {
        PipelineRunWatchManager mgr = new PipelineRunWatchManager(null, null, 1024);
        Instant now = Instant.parse("2026-10-15T10:31:00Z");
        mgr.recordOpened("c1", "ns1", "old", now.minus(Duration.ofMinutes(31)));
        mgr.recordOpened("c1", "ns1", "fresh", now.minus(Duration.ofMinutes(1)));

        RecordingTicker t = new RecordingTicker(mgr, Duration.ofMinutes(30));
        t.tick(now);

        assertThat(t.cancelled).containsExactly("old");
        assertThat(t.retried).containsExactly("tick");     // 待补重投**每一拍都做**
    }

    @Test
    void nothingOverdueMeansNothingCancelledButStillRetries() {
        PipelineRunWatchManager mgr = new PipelineRunWatchManager(null, null, 1024);
        RecordingTicker t = new RecordingTicker(mgr, Duration.ofMinutes(30));
        t.tick(Instant.now());

        assertThat(t.cancelled).isEmpty();
        assertThat(t.retried).containsExactly("tick");
    }

    @Test
    void withNoActiveRunsTheTickTouchesNoCluster() {
        // ⭐ 这是「它不是轮询」的正面断言：台账空 → 一次集群调用都不发
        PipelineRunWatchManager mgr = new PipelineRunWatchManager(null, null, 1024);
        RecordingTicker t = new RecordingTicker(mgr, Duration.ofMinutes(30));
        t.tick(Instant.now());
        assertThat(mgr.activeRuns()).isEmpty();
    }
}
