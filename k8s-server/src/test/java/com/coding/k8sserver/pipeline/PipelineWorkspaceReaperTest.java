package com.coding.k8sserver.pipeline;

import com.coding.common.models.k8s.dto.PersistentVolumeClaimDTO;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineWorkspaceReaperTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    private static PersistentVolumeClaimDTO pvc(String name, String retainUntil) {
        PersistentVolumeClaimDTO dto = new PersistentVolumeClaimDTO();
        dto.setName(name);
        dto.setNamespace("ns1");
        dto.setAnnotations(retainUntil == null ? Map.of()
                : Map.of(PipelineWorkspaceReaper.RETAIN_UNTIL, retainUntil));
        return dto;
    }

    @Test
    void anExpiredWorkspaceIsReapable() {
        assertThat(PipelineWorkspaceReaper.expired(NOW, List.of(
                pvc("old", "2026-10-20T11:59:59Z"),
                pvc("fresh", "2026-10-20T12:00:01Z"))))
                .extracting(PersistentVolumeClaimDTO::getName)
                .containsExactly("old");
    }

    @Test
    void aWorkspaceWithoutAParsableDeadlineIsLeftAlone() {
        // 看不懂就不删 —— 猜错一次就是删掉一块别人正在等着的卷
        assertThat(PipelineWorkspaceReaper.expired(NOW, List.of(
                pvc("no-annotation", null),
                pvc("garbage", "tomorrow-ish"))))
                .isEmpty();
    }

    @Test
    void anEmptyListIsHarmless() {
        assertThat(PipelineWorkspaceReaper.expired(NOW, List.of())).isEmpty();
        assertThat(PipelineWorkspaceReaper.expired(NOW, null)).isEmpty();
    }
}
