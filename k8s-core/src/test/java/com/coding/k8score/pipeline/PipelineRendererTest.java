package com.coding.k8score.pipeline;

import com.coding.common.exception.CloudPlatformException;
import io.fabric8.tekton.v1.PipelineSpec;
import io.fabric8.tekton.v1.PipelineTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineRendererTest {

    private final PipelineNodeRegistry registry =
            new PipelineNodeRegistry(List.of(new GitNode(), new EchoNode()));
    private final PipelineRenderer renderer = new PipelineRenderer(registry);

    /** 两个只为测试存在的节点：git 产出一个 step，echo 产出一个 step */
    static class GitNode implements PipelineNode {
        @Override public String type() { return "git"; }
        @Override public NodeSchema formSchema() { return new NodeSchema(List.of(NodeField.text("repo", "仓库", true))); }
        @Override public RenderedStep render(NodeContext ctx) {
            return new RenderedStep(List.of(newStep("clone-" + ctx.nodeName())));
        }
    }
    static class EchoNode implements PipelineNode {
        @Override public String type() { return "echo"; }
        @Override public NodeSchema formSchema() { return new NodeSchema(List.of(NodeField.text("msg", "消息", false))); }
        @Override public RenderedStep render(NodeContext ctx) {
            return new RenderedStep(List.of(newStep("echo-" + ctx.nodeName())));
        }
    }
    private static io.fabric8.tekton.v1.Step newStep(String name) {
        io.fabric8.tekton.v1.Step s = new io.fabric8.tekton.v1.Step();
        s.setName(name);
        s.setImage("alpine:3");
        return s;
    }

    private static PipelineCanvas canvas(List<PipelineCanvas.CanvasNode> nodes, List<PipelineCanvas.CanvasEdge> edges) {
        return new PipelineCanvas(nodes, edges);
    }

    private static PipelineCanvas.CanvasNode node(String id, String type) {
        return new PipelineCanvas.CanvasNode(id, type, Map.of("repo", "x", "msg", "y"));
    }

    @Test
    void aSingleNodeBecomesOnePipelineTask() {
        PipelineSpec spec = renderer.render(
                canvas(List.of(node("n1", "git")), List.of()), "ws");

        assertThat(spec.getTasks()).hasSize(1);
        assertThat(spec.getTasks().get(0).getName()).isEqualTo("n1");
    }

    @Test
    void anEdgeBecomesRunAfter() {
        PipelineSpec spec = renderer.render(
                canvas(List.of(node("n1", "git"), node("n2", "echo")),
                        List.of(new PipelineCanvas.CanvasEdge("n1", "n2"))), "ws");

        PipelineTask second = spec.getTasks().stream()
                .filter(t -> t.getName().equals("n2")).findFirst().orElseThrow();
        assertThat(second.getRunAfter()).containsExactly("n1");
    }

    @Test
    void unconnectedNodesAreConcurrentNotBroken() {
        // 没有连线 = 并发，这是 Tekton 的默认语义。**不能被当成"没连上"而拒掉** ——
        // UI 上要给这个语义提示，渲染器则必须原样产出一个不带 runAfter 的 task
        PipelineSpec spec = renderer.render(
                canvas(List.of(node("n1", "git"), node("n2", "echo")), List.of()), "ws");

        assertThat(spec.getTasks()).hasSize(2);
        assertThat(spec.getTasks()).allSatisfy(t -> assertThat(t.getRunAfter()).isNullOrEmpty());
    }

    @Test
    void forkThenJoinKeepsBothEdges() {
        PipelineSpec spec = renderer.render(
                canvas(List.of(node("a", "git"), node("b", "echo"), node("c", "echo"), node("d", "echo")),
                        List.of(new PipelineCanvas.CanvasEdge("a", "b"),
                                new PipelineCanvas.CanvasEdge("a", "c"),
                                new PipelineCanvas.CanvasEdge("b", "d"),
                                new PipelineCanvas.CanvasEdge("c", "d"))), "ws");

        PipelineTask d = spec.getTasks().stream().filter(t -> t.getName().equals("d")).findFirst().orElseThrow();
        assertThat(d.getRunAfter()).containsExactlyInAnyOrder("b", "c");
    }

    @Test
    void aCycleIsRejectedNotRendered() {
        // ⭐ Review Focus #1：连成 A→B→A。绝不能让一个环走到集群去
        assertThatThrownBy(() -> renderer.render(
                canvas(List.of(node("a", "git"), node("b", "echo")),
                        List.of(new PipelineCanvas.CanvasEdge("a", "b"),
                                new PipelineCanvas.CanvasEdge("b", "a"))), "ws"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("环");
    }

    @Test
    void selfLoopIsAlsoACycle() {
        assertThatThrownBy(() -> renderer.render(
                canvas(List.of(node("a", "git")),
                        List.of(new PipelineCanvas.CanvasEdge("a", "a"))), "ws"))
                .isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void anEdgePointingAtAnUnknownNodeIsRejected() {
        assertThatThrownBy(() -> renderer.render(
                canvas(List.of(node("a", "git")),
                        List.of(new PipelineCanvas.CanvasEdge("a", "ghost"))), "ws"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void anEmptyCanvasIsRejected() {
        assertThatThrownBy(() -> renderer.render(canvas(List.of(), List.of()), "ws"))
                .isInstanceOf(CloudPlatformException.class);
    }

    @Test
    void everyNodeGetsTheSharedWorkspaceWithoutTheUserKnowing() {
        PipelineSpec spec = renderer.render(
                canvas(List.of(node("n1", "git"), node("n2", "echo")), List.of()), "ws");

        assertThat(spec.getWorkspaces()).extracting(w -> w.getName()).containsExactly("ws");
    }

    @Test
    void renderingIsDeterministic() {
        // 这是「历史运行天然带当时那一版」的前提 —— 同一份画布渲染两次必须字节等价
        PipelineCanvas c = canvas(List.of(node("n1", "git"), node("n2", "echo")), List.of());
        assertThat(renderer.render(c, "ws")).isEqualTo(renderer.render(c, "ws"));
    }
}
