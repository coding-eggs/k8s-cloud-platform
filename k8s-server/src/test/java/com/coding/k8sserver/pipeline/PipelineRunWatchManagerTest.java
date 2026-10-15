package com.coding.k8sserver.pipeline;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineRunWatchManagerTest {

    /** 只测**台账与超时判定** —— 真正的 watch 调用靠部署后冒烟。 */
    private final PipelineRunWatchManager mgr = new PipelineRunWatchManager(null, null, 1024);

    @Test
    void anOpenedRunShowsUpInTheLedgerWithItsStartTime() {
        Instant t0 = Instant.parse("2026-10-15T10:00:00Z");
        mgr.recordOpened("c1", "ns1", "r1", t0);

        assertThat(mgr.activeRuns()).singleElement().satisfies(r -> {
            assertThat(r.clusterId()).isEqualTo("c1");
            assertThat(r.namespace()).isEqualTo("ns1");
            assertThat(r.runName()).isEqualTo("r1");
            assertThat(r.startedAt()).isEqualTo(t0);
        });
    }

    @Test
    void aRunOverItsTimeoutIsReportedButNothingElseIs() {
        Instant now = Instant.parse("2026-10-15T10:31:00Z");
        mgr.recordOpened("c1", "ns1", "old", now.minus(Duration.ofMinutes(31)));
        mgr.recordOpened("c1", "ns1", "fresh", now.minus(Duration.ofMinutes(5)));

        // ⚠️ 只看台账、**不打集群** —— 这是本方法之所以不是"轮询"的全部理由
        assertThat(mgr.overdue(now, Duration.ofMinutes(30)))
                .extracting(PipelineRunWatchManager.ActiveRun::runName)
                .containsExactly("old");
    }

    @Test
    void closingARunRemovesItFromTheLedger() {
        mgr.recordOpened("c1", "ns1", "r1", Instant.now());
        mgr.recordClosed("c1", "ns1", "r1");
        assertThat(mgr.activeRuns()).isEmpty();
    }

    @Test
    void closingAnUnknownRunIsANoOpNotAnError() {
        // 幂等：回调成功与 watch 关闭可能以任意顺序发生
        mgr.recordClosed("c1", "ns1", "ghost");
        assertThat(mgr.activeRuns()).isEmpty();
    }
}
