package com.coding.k8score.pipeline;

import com.coding.common.exception.CloudPlatformException;
import com.coding.k8score.pipeline.PipelineCanvas.CanvasEdge;
import com.coding.k8score.pipeline.PipelineCanvas.CanvasNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineNodeRegistryTest {

    /** 只为测试存在的节点 */
    static class FakeNode implements PipelineNode {
        @Override public String type() { return "fake"; }
        @Override public NodeSchema formSchema() {
            return new NodeSchema(List.of(NodeField.text("msg", "消息", false)));
        }
        @Override public RenderedStep render(NodeContext ctx) {
            return new RenderedStep(List.of());
        }
    }

    @Test
    void dispatchesByType() {
        PipelineNodeRegistry r = new PipelineNodeRegistry(List.of(new FakeNode()));
        assertThat(r.of("fake")).isInstanceOf(FakeNode.class);
        assertThat(r.supports("fake")).isTrue();
        assertThat(r.supports("nope")).isFalse();
    }

    @Test
    void unknownTypeFailsWithABusinessCodeNamingTheType() {
        PipelineNodeRegistry r = new PipelineNodeRegistry(List.of(new FakeNode()));
        assertThatThrownBy(() -> r.of("nope"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void duplicateTypeIsAProgrammingErrorNotLastOneWins() {
        // 两个节点类声明了同一个 type —— 静默"后者覆盖"会让一个节点在画布上可用、
        // 渲染时变成另一个。构建期就炸比运行时诡异好。
        assertThatThrownBy(() -> new PipelineNodeRegistry(List.of(new FakeNode(), new FakeNode())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fake");
    }

    @Test
    void canvasRoundTripsThroughJson() {
        PipelineCanvas c = new PipelineCanvas(
                List.of(new CanvasNode("n1", "fake", "拉取代码", Map.of("msg", "hi"))),
                List.of(new CanvasEdge("n1", "n2")));
        String json = PipelineCanvas.toJson(c);
        assertThat(PipelineCanvas.fromJson(json)).isEqualTo(c);
    }

    @Test
    void anAlreadySavedCanvasWithoutNamesStillParses() {
        // 显示名是这一版才加的字段：库里已存的画布没有它。**必须照常解析**（name = null，
        // 各处退回显示 id），否则用户打开一条旧流水线就直接报「画布内容无法解析」
        PipelineCanvas c = PipelineCanvas.fromJson(
                "{\"nodes\":[{\"id\":\"clone\",\"type\":\"git\",\"config\":{\"repo\":\"x\"}}],\"edges\":[]}");

        assertThat(c.nodes()).hasSize(1);
        assertThat(c.nodes().get(0).id()).isEqualTo("clone");
        assertThat(c.nodes().get(0).name()).isNull();
    }

    @Test
    void malformedCanvasJsonIsRejectedNotSilentlyEmpty() {
        // 画布 JSON 是唯一真相源。解析成"空画布"会让用户以为自己的流水线没了
        assertThatThrownBy(() -> PipelineCanvas.fromJson("{not json"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("画布");
    }
}
