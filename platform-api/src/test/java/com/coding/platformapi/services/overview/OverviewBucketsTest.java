package com.coding.platformapi.services.overview;

import com.coding.data.models.system.StatusCount;
import com.coding.platformapi.models.overview.CountBreakdown;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OverviewBucketsTest {

    private static StatusCount sc(String status, long count) {
        StatusCount s = new StatusCount();
        s.setStatus(status);
        s.setCount(count);
        return s;
    }

    @Test
    void applicationsMapsDraftToUnreleasedAndSkipsDeleting() {
        CountBreakdown b = OverviewBuckets.applications(List.of(sc("READY", 3), sc("DRAFT", 1), sc("DELETING", 2)));

        assertThat(b.total()).isEqualTo(4);
        assertThat(b.byStatus()).containsExactly(
                java.util.Map.entry("RUNNING", 3L), java.util.Map.entry("DEPLOYING", 0L),
                java.util.Map.entry("UNRELEASED", 1L), java.util.Map.entry("FAILED", 0L),
                java.util.Map.entry("OFFLINE", 0L));
    }

    @Test
    void pipelinesFoldTimeoutIntoFailedAndNullIntoNever() {
        CountBreakdown b = OverviewBuckets.pipelines(List.of(sc("TIMEOUT", 1), sc("FAILED", 2), sc(null, 4)));

        assertThat(b.total()).isEqualTo(7);
        assertThat(b.byStatus().get("FAILED")).isEqualTo(3L);
        assertThat(b.byStatus().get("NEVER")).isEqualTo(4L);
    }

    @Test
    void alertsBucketUnknownSeverityAsOther() {
        CountBreakdown b = OverviewBuckets.alerts(List.of(sc("Critical", 1), sc("warning", 2), sc(null, 1), sc("info", 3)));

        assertThat(b.byStatus()).containsEntry("CRITICAL", 1L).containsEntry("WARNING", 2L).containsEntry("OTHER", 4L);
        assertThat(b.total()).isEqualTo(7);
    }

    @Test
    void middlewareDegradedIsAbnormal() {
        CountBreakdown b = OverviewBuckets.middleware(Arrays.asList("READY", "DEGRADED", "FAILED", "CREATING"));

        assertThat(b.byStatus()).containsEntry("NORMAL", 1L).containsEntry("ABNORMAL", 2L).containsEntry("PROCESSING", 1L);
        assertThat(b.total()).isEqualTo(4);
    }
}
