package com.coding.k8score.converter.impl.core;

import com.coding.common.models.k8s.dto.PersistentVolumeClaimDTO;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimCondition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PVC 状态里那段**只能从 `conditions` 看出来**的东西（S4.1.3）。
 *
 * <p>为什么值得单独钉：扩容的中间态**不体现在 `phase` 上** —— 扩容期间 `phase` 一直是
 * {@code Bound}，只有条件里才有 {@code Resizing} / {@code FileSystemResizePending}。
 * 不透出来的话，UI 上「正在扩容」看起来和「已经好了」一模一样，运维会把正常过程当成故障处置。
 */
class CoreV1PvcConverterTest {

    private final CoreV1PvcConverter converter = new CoreV1PvcConverter();

    private static PersistentVolumeClaimCondition condition(String type, String status) {
        PersistentVolumeClaimCondition c = new PersistentVolumeClaimCondition();
        c.setType(type);
        c.setStatus(status);
        return c;
    }

    private static PersistentVolumeClaim pvcWith(List<PersistentVolumeClaimCondition> conditions) {
        return new PersistentVolumeClaimBuilder()
                .withNewMetadata().withName("data").withNamespace("ns1").endMetadata()
                .withNewStatus()
                    .withPhase("Bound")   // ⚠️ 扩容期间它就是 Bound —— 所以光看它什么都看不出来
                    .withConditions(conditions)
                .endStatus()
                .build();
    }

    @Test
    void onlyTheConditionsThatAreCurrentlyTrueSurvive() {
        PersistentVolumeClaimDTO dto = converter.revert(pvcWith(List.of(
                condition("Resizing", "True"),
                condition("FileSystemResizePending", "True"),
                condition("ControllerResizeError", "False"))));

        assertThat(dto.getConditions()).containsExactlyInAnyOrder("Resizing", "FileSystemResizePending");
        assertThat(dto.getConditions()).doesNotContain("ControllerResizeError");
    }

    @Test
    void theTwoWaysOfHavingNoConditionsBothSurviveTheRoundTrip() {
        // 「没有条件」有两种形状：`status.conditions` 是**空列表**（builder 造出来的就是这样），
        // 和**压根没有 status**（那就是 null）。两种都留着不动 —— 前端一律按 `?? []` 处理，
        // 后端不为了"形状好看"去造一个空列表。
        assertThat(converter.revert(pvcWith(List.of())).getConditions()).isEmpty();

        PersistentVolumeClaim bare = new PersistentVolumeClaimBuilder()
                .withNewMetadata().withName("data").withNamespace("ns1").endMetadata()
                .build();
        assertThat(converter.revert(bare).getPhase()).isNull();
        assertThat(converter.revert(bare).getConditions()).isNull();
    }
}
