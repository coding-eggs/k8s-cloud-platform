package com.coding.k8score.pipeline;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tekton 的 {@code coschedule} 解析。
 *
 * <p>⚠️ 这个值决定**构建缓存能不能开**：非 {@code pipelineruns} 时一个 TaskRun 挂不了两块
 * PVC workspace（Tekton 直接判运行失败），所以要精确 —— 特别是"键缺失"这一格：
 * 那是 Tekton 用它的默认值（{@code workspaces}），不是"读不到"。
 */
class TektonSchedulingTest {

    @Test
    void pipelinerunsIsTheOnlyValueThatAllowsTwoPvcWorkspaces() {
        assertThat(TektonScheduling.allowsMultiplePvcWorkspaces(Map.of("coschedule", "pipelineruns")))
                .isTrue();
        assertThat(TektonScheduling.allowsMultiplePvcWorkspaces(Map.of("coschedule", "workspaces")))
                .isFalse();
        // 另外两个合法值也一样不允许（isolate-pipelinerun 只多一条"一个节点同时只跑一条"）
        assertThat(TektonScheduling.allowsMultiplePvcWorkspaces(Map.of("coschedule", "isolate-pipelinerun")))
                .isFalse();
        assertThat(TektonScheduling.allowsMultiplePvcWorkspaces(Map.of("coschedule", "disabled")))
                .isFalse();
    }

    @Test
    void aMissingKeyMeansTektonUsesItsOwnDefault() {
        // ⚠️ 这一格最容易写错：键缺失 ≠ 读不到 —— 是 Tekton 会用 workspaces，也就是**真的没开**
        assertThat(TektonScheduling.coscheduleOf(Map.of())).isEqualTo("workspaces");
        assertThat(TektonScheduling.coscheduleOf(new HashMap<>())).isEqualTo("workspaces");
        assertThat(TektonScheduling.coscheduleOf(null)).isEqualTo("workspaces");
        assertThat(TektonScheduling.coscheduleOf(Map.of("coschedule", "   "))).isEqualTo("workspaces");
    }

    @Test
    void theValueIsTrimmedAndCaseSensitive() {
        // YAML 里手写容易带空格；值是枚举，不做大小写宽松匹配（写错了就该当作没开）
        assertThat(TektonScheduling.coscheduleOf(Map.of("coschedule", " pipelineruns ")))
                .isEqualTo("pipelineruns");
        assertThat(TektonScheduling.allowsMultiplePvcWorkspaces(Map.of("coschedule", "PipeLineRuns")))
                .isFalse();
    }

    @Test
    void otherKeysInTheConfigMapDoNotLeak() {
        // 那个 ConfigMap 里还有一堆别的开关 —— 解析只看 coschedule
        Map<String, String> data = new HashMap<>();
        data.put("enable-api-fields", "beta");
        data.put("coschedule", "pipelineruns");
        assertThat(TektonScheduling.allowsMultiplePvcWorkspaces(data)).isTrue();
    }
}
