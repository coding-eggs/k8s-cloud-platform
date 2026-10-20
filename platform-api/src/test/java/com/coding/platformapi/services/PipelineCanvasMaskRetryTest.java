package com.coding.platformapi.services;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 断点规则是**重试的全部安全边界**：C 在 B 失败时不能当起点；并行的断点必须一起补。
 */
class PipelineCanvasMaskRetryTest {

    private static final String CHAIN = """
            {"nodes":[
              {"id":"a","type":"shell","config":{}},
              {"id":"b","type":"shell","config":{}},
              {"id":"c","type":"shell","config":{}}
            ],"edges":[{"from":"a","to":"b"},{"from":"b","to":"c"}]}""";

    /** 两分支：git → a1 → a2 → a3；git → b1 → b2；外加收尾节点 fin。 */
    private static final String FORK = """
            {"nodes":[
              {"id":"git","type":"git","config":{}},
              {"id":"a1","type":"shell","config":{}},
              {"id":"a2","type":"shell","config":{}},
              {"id":"a3","type":"shell","config":{}},
              {"id":"b1","type":"shell","config":{}},
              {"id":"b2","type":"shell","config":{}},
              {"id":"fin","type":"shell","config":{},"finallyTask":true}
            ],"edges":[{"from":"git","to":"a1"},{"from":"a1","to":"a2"},{"from":"a2","to":"a3"},
                       {"from":"git","to":"b1"},{"from":"b1","to":"b2"}]}""";

    @Test
    void onlyTheFailedNodeIsTheBreakpointWhenItsUpstreamSucceeded() {
        // a→b→c，b 失败：c 的上游没成功 → c **不是**断点
        var plan = PipelineCanvasMask.plan(CHAIN,
                Map.of("a", "SUCCEEDED", "b", "FAILED"), Set.of());

        assertThat(plan.retryable()).containsExactly("b");
        assertThat(plan.runNodeIds()).containsExactly("b", "c");
        assertThat(plan.blocked()).containsEntry("c", "上游 b 没有成功，请从它开始重试")
                .containsEntry("a", "这个节点已经成功，不需要重试");
    }

    @Test
    void bothBranchesBreakpointsAreRetriedTogether() {
        // A2 失败把正在跑的 B1 取消：B1 也是断点，必须一起补 —— 否则 B 永远补不了。
        // 收尾节点置为已成功，把这个用例聚焦在「并行断点合并」上（收尾可重试另有专门的用例）。
        var plan = PipelineCanvasMask.plan(FORK,
                Map.of("git", "SUCCEEDED", "a1", "SUCCEEDED", "a2", "FAILED", "b1", "CANCELLED",
                        "fin", "SUCCEEDED"),
                Set.of());

        assertThat(plan.retryable()).containsExactlyInAnyOrder("a2", "b1");
        assertThat(plan.runNodeIds()).containsExactlyInAnyOrder("a2", "a3", "b1", "b2");
    }

    @Test
    void aNodeWithoutAnyStatusCountsAsNotSucceeded() {
        // 没轮到它 → 没有状态 → 如果上游成功，它就是断点
        var plan = PipelineCanvasMask.plan(CHAIN, Map.of("a", "SUCCEEDED"), Set.of());
        assertThat(plan.retryable()).containsExactly("b");
        assertThat(plan.runNodeIds()).containsExactly("b", "c");
    }

    @Test
    void maskedNodesAreNeverRetryableAndStayOutOfTheScope() {
        var plan = PipelineCanvasMask.plan(FORK,
                Map.of("git", "SUCCEEDED", "a1", "SUCCEEDED", "a2", "FAILED", "b1", "CANCELLED",
                        "fin", "SUCCEEDED"),
                Set.of("b1", "b2"));

        assertThat(plan.retryable()).containsExactly("a2");
        assertThat(plan.runNodeIds()).containsExactly("a2", "a3");
        assertThat(plan.blocked()).containsEntry("b1", "本次运行屏蔽了它（连同下游）");
    }

    @Test
    void anUnfinishedFinallyNodeIsRetryableOnItsOwnButNotInTheScope() {
        var plan = PipelineCanvasMask.plan(FORK,
                Map.of("git", "SUCCEEDED", "a1", "SUCCEEDED", "a2", "FAILED", "b1", "CANCELLED"),
                Set.of());

        assertThat(plan.retryable()).contains("fin");
        assertThat(plan.runNodeIds()).doesNotContain("fin");
    }

    @Test
    void aSucceededFinallyNodeIsNotRetryable() {
        var plan = PipelineCanvasMask.plan(FORK,
                Map.of("git", "SUCCEEDED", "a1", "SUCCEEDED", "a2", "FAILED", "b1", "CANCELLED",
                        "fin", "SUCCEEDED"),
                Set.of());
        assertThat(plan.retryable()).doesNotContain("fin");
        assertThat(plan.blocked()).containsEntry("fin", "这个节点已经成功，不需要重试");
    }
}
