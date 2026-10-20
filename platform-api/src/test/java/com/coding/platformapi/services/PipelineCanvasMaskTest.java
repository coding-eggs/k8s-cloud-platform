package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineCanvasMaskTest {

    /** a → b → c，外加一个收尾节点 fin（不参与连线）。 */
    private static final String CHAIN = """
            {"nodes":[
              {"id":"a","type":"shell","name":"克隆","config":{"command":"echo a"},"position":{"x":1,"y":2}},
              {"id":"b","type":"shell","name":"构建","config":{"command":"echo b"},"onError":"continue"},
              {"id":"c","type":"shell","config":{"command":"echo c"}},
              {"id":"fin","type":"shell","config":{"command":"echo done"},"finallyTask":true,"finallyWhen":"failed"}
            ],"edges":[{"from":"a","to":"b"},{"from":"b","to":"c"}]}""";

    @Test
    void maskingALeafOnlyMasksThatLeaf() {
        assertThat(PipelineCanvasMask.maskedClosure(CHAIN, List.of("c")))
                .containsExactly("c");
    }

    @Test
    void maskingAMiddleNodeTakesItsWholeSubtree() {
        assertThat(PipelineCanvasMask.maskedClosure(CHAIN, List.of("b")))
                .containsExactly("b", "c");
    }

    @Test
    void anUnknownNodeIdIsRejectedNotIgnored() {
        assertThatThrownBy(() -> PipelineCanvasMask.maskedClosure(CHAIN, List.of("nope")))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void trimKeepsTheRequestedNodesAndTheirAttributesVerbatim() {
        String out = PipelineCanvasMask.trim(CHAIN, Set.of("a", "b"), null);
        assertThat(out).contains("\"id\":\"a\"").contains("\"name\":\"克隆\"")
                .contains("\"position\":{\"x\":1,\"y\":2}").contains("\"onError\":\"continue\"")
                .doesNotContain("\"id\":\"c\"").doesNotContain("\"id\":\"fin\"");
    }

    @Test
    void trimDropsEdgesWhoseEitherEndIsGone() {
        String out = PipelineCanvasMask.trim(CHAIN, Set.of("a", "b"), null);
        assertThat(out).contains("\"from\":\"a\",\"to\":\"b\"");
        assertThat(out).doesNotContain("\"from\":\"b\",\"to\":\"c\"");
    }

    @Test
    void retryingAFinallyNodeClearsItsFinallyFlags() {
        // 收尾节点单独重跑时必须当普通任务：否则「只有 finally、没有 tasks」的 spec 不成立
        String out = PipelineCanvasMask.trim(CHAIN, Set.of("fin"), "fin");
        assertThat(out).doesNotContain("finallyTask").doesNotContain("finallyWhen");
    }

    @Test
    void trimmingEverythingRunnableIsRejected() {
        // 只留下收尾节点、且它不是重试目标 → 没有任何普通节点可跑
        assertThatThrownBy(() -> PipelineCanvasMask.trim(CHAIN, Set.of("fin"), null))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("没有可运行");
    }

    @Test
    void isFinallyReadsTheCanvasFlag() {
        assertThat(PipelineCanvasMask.isFinally(CHAIN, "fin")).isTrue();
        assertThat(PipelineCanvasMask.isFinally(CHAIN, "a")).isFalse();
    }
}
