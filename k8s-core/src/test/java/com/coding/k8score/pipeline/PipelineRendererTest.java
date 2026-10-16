package com.coding.k8score.pipeline;

import com.coding.common.exception.CloudPlatformException;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.tekton.v1.PipelineSpec;
import io.fabric8.tekton.v1.PipelineTask;
import io.fabric8.tekton.v1.WhenExpression;
import io.fabric8.tekton.v1.WorkspaceDeclaration;
import io.fabric8.tekton.v1.WorkspacePipelineTaskBinding;
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

    /** 把"这次渲染给我的时刻"记下来 —— 用来钉住「整张画布只取一次时刻」。 */
    static class ClockNode implements PipelineNode {
        final List<java.time.LocalDateTime> seen = new java.util.ArrayList<>();
        @Override public String type() { return "clock"; }
        @Override public NodeSchema formSchema() { return new NodeSchema(List.of(NodeField.text("x", "x", false))); }
        @Override public RenderedStep render(NodeContext ctx) {
            // ⚠️ 这个 sleep 是**测试能成立的前提**，不是随手加的：Windows 上 `LocalDateTime.now()`
            //    是**毫秒**粒度，两次紧挨着的调用会落在同一毫秒里、比出来相等 ——
            //    那样"整张画布只取一次时刻"这条断言就成了摆设（第一版正是如此，
            //    变异成"每个节点各取一次 now()"之后测试照样绿，被变异测试戳穿了）。
            //    睡过时钟粒度之后，"各取一次"才会真的差开，这条断言才算数。
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            seen.add(ctx.renderedAt());
            return new RenderedStep(List.of(newStep("clock-" + ctx.nodeName())));
        }
    }

    @Test
    void theWholeCanvasIsRenderedWithOneSingleInstant() {
        // ⭐ 一张画布上两个镜像节点（web + auth）必须拿到**同一个** tag ——
        //    那正是"同一次运行的产物配套"的意思。渲染器若给每个节点各取一次 now()，
        //    两个 tag 就会差几百微秒、彻底对不上。（时间戳当 tag 是新功能，这条是它的地基。）
        ClockNode clock = new ClockNode();
        PipelineRenderer r = new PipelineRenderer(new PipelineNodeRegistry(List.of(clock)));

        r.render(canvas(List.of(node("n1", "clock"), node("n2", "clock")), List.of()), "ws");

        assertThat(clock.seen).hasSize(2).doesNotContainNull();
        assertThat(clock.seen.get(0)).isEqualTo(clock.seen.get(1));
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
        return new PipelineCanvas.CanvasNode(id, type, null, Map.of("repo", "x", "msg", "y"));
    }

    /** 起了显示名的节点（名字只给人看，不进 task 名） */
    private static PipelineCanvas.CanvasNode named(String id, String type, String name) {
        return new PipelineCanvas.CanvasNode(id, type, name, Map.of("repo", "x", "msg", "y"));
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
    void aNodeNameBecomesTheDisplayNameNotTheTaskName() {
        // ⭐ 名字是**给人看**的（可以中文），id 才是 Tekton 的 task 名：
        // 日志、运行状态、历史键（stepsJson）全按 id 走，所以起个名字不动任何契约
        PipelineSpec spec = renderer.render(
                canvas(List.of(named("git-l8x31", "git", "拉取代码")), List.of()), "ws");

        PipelineTask task = spec.getTasks().get(0);
        assertThat(task.getName()).isEqualTo("git-l8x31");
        assertThat(task.getDisplayName()).isEqualTo("拉取代码");
    }

    @Test
    void aNodeWithoutANameGetsNoDisplayName() {
        // 没起名 = 不写 displayName（而不是写个空串），这样渲染结果和这一版之前一模一样
        PipelineSpec spec = renderer.render(canvas(List.of(node("n1", "git")), List.of()), "ws");

        assertThat(spec.getTasks().get(0).getDisplayName()).isNull();
    }

    @Test
    void aWhitespaceOnlyNameCountsAsUnnamed() {
        PipelineSpec spec = renderer.render(canvas(List.of(named("n1", "git", "   ")), List.of()), "ws");

        assertThat(spec.getTasks().get(0).getDisplayName()).isNull();
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

    // ==================== 运行控制：失败也继续 / 收尾执行 ====================
    //
    // ⚠️ 这两个开关为什么**只**做到这一步（2026-10-16 用 --dry-run=server 实测过）：
    //   · `tasks[]` 里的 `when` **不能引用任何任务的执行状态** —— webhook 直接拒：
    //       invalid value: pipeline tasks can not refer to execution status of any other
    //       pipeline task or aggregate status of tasks
    //     所以「上游失败才走这条线」「上游成功才走这条线」**在 tasks[] 里表达不出来**。
    //     （顺带：上游失败本来就会跳过所有下游，所以「成功才继续」是 Tekton 的默认行为，不用开关。）
    //   · 执行状态条件**只有 finally 能用**（`$(tasks.status)` / `$(tasks.X.status)`）。
    //   · finally **没有 runAfter、也不按顺序跑** —— 它们同时开跑。
    // 于是「失败成功如何走」在这套画布上的落点就是下面这两个字段。

    @Test
    void aNodeThatContinuesOnErrorRendersOnErrorContinue() {
        PipelineSpec spec = renderer.render(
                canvas(List.of(node("n1", "git"), withRun("n2", "echo", PipelineRenderer.CONTINUE, null, null)),
                        List.of(new PipelineCanvas.CanvasEdge("n1", "n2"))), "ws");

        PipelineTask second = task(spec, "n2");
        assertThat(second.getOnError()).isEqualTo("continue");
        assertThat(second.getRunAfter()).containsExactly("n1");   // 开关不动连线语义
    }

    @Test
    void anUnknownOnErrorIsRejected() {
        // 同理：存下来的画布是外部输入，认不出来的失败处理不能**静默丢掉**（那等于开关没生效）
        assertThatThrownBy(() -> renderer.render(
                canvas(List.of(withRun("n1", "git", "explode", null, null)), List.of()), "ws"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("失败处理")
                .hasMessageContaining("n1");
    }

    @Test
    void aNodeWithoutRunControlRendersNeitherOnErrorNorFinally() {
        // 没勾任何开关 → 一个字段都不多写（老画布渲染出来与加这个功能之前逐字节一致）
        PipelineSpec spec = renderer.render(canvas(List.of(node("n1", "git")), List.of()), "ws");

        assertThat(spec.getTasks().get(0).getOnError()).isNull();
        assertThat(spec.getFinally()).isNullOrEmpty();
    }

    @Test
    void withoutFinallyNodesTheRenderedYamlHasNoFinallySection() {
        // 普通流水线的 YAML 里不该冒出一个空的 `finally:`（老运行的 YAML 还要能对着看）
        String yaml = Serialization.asYaml(
                renderer.render(canvas(List.of(node("n1", "git")), List.of()), "ws"));

        assertThat(yaml).doesNotContain("finally");
    }

    @Test
    void aFinallyNodeLandsInTheFinallyListNotInTasks() {
        PipelineSpec spec = renderer.render(
                canvas(List.of(node("n1", "git"), withRun("n9", "echo", null, true, null)), List.of()), "ws");

        assertThat(spec.getTasks()).extracting(PipelineTask::getName).containsExactly("n1");
        assertThat(spec.getFinally()).extracting(PipelineTask::getName).containsExactly("n9");
    }

    @Test
    void finallyRunsAlwaysWhenNoTimingIsChosen() {
        PipelineSpec spec = renderer.render(
                canvas(List.of(node("n1", "git"), withRun("n9", "echo", null, true, null)), List.of()), "ws");

        assertThat(finallyTask(spec, "n9").getWhen()).isNullOrEmpty();
    }

    @Test
    void finallyOnFailureAsksForAnythingButSuccess() {
        // ⚠️ 有意**不**写 values: ["Failed"]：聚合值的档位是 Succeeded / Completed / Failed / None，
        //    而 Completed 那一档的含义是「有任务被跳过」—— 平台上唯一能造成跳过的原因就是上游失败，
        //    所以「只要不是全成功、也不是全跳过」才是「前面失败了」的准确表达（两种档位口径下都成立）。
        PipelineSpec spec = renderer.render(
                canvas(List.of(node("n1", "git"),
                        withRun("n9", "echo", null, true, PipelineRenderer.WHEN_FAILED)), List.of()), "ws");

        WhenExpression when = onlyWhen(spec, "n9");
        assertThat(when.getInput()).isEqualTo("$(tasks.status)");
        assertThat(when.getOperator()).isEqualTo("notin");
        assertThat(when.getValues()).containsExactly("Succeeded", "None");
    }

    @Test
    void finallyOnSuccessRunsOnlyWhenEverythingSucceeded() {
        PipelineSpec spec = renderer.render(
                canvas(List.of(node("n1", "git"),
                        withRun("n9", "echo", null, true, PipelineRenderer.WHEN_SUCCEEDED)), List.of()), "ws");

        WhenExpression when = onlyWhen(spec, "n9");
        assertThat(when.getInput()).isEqualTo("$(tasks.status)");
        assertThat(when.getOperator()).isEqualTo("in");
        assertThat(when.getValues()).containsExactly("Succeeded");
    }

    @Test
    void anUnknownFinallyTimingIsRejected() {
        // 存下来的画布是**外部输入**（前端可以绕过），未知取值不能静默当成"总是执行"
        assertThatThrownBy(() -> renderer.render(
                canvas(List.of(node("n1", "git"), withRun("n9", "echo", null, true, "什么时候都行")), List.of()),
                "ws"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("收尾时机");
    }

    @Test
    void aFinallyNodeKeepsTheSharedWorkspaceBound() {
        // 收尾节点走的是同一条构造路径 —— 少一个 workspace 绑定就整个 TaskRun 起不来
        // （declared workspace "ws" is required but has not been bound）
        PipelineSpec spec = renderer.render(
                canvas(List.of(node("n1", "git"), withRun("n9", "echo", null, true, null)), List.of()), "ws");

        PipelineTask n9 = finallyTask(spec, "n9");
        assertThat(n9.getTaskSpec().getWorkspaces())
                .extracting(WorkspaceDeclaration::getName).containsExactly("ws");
        assertThat(n9.getWorkspaces())
                .extracting(WorkspacePipelineTaskBinding::getName).containsExactly("ws");
    }

    @Test
    void aFinallyNodeCanAlsoContinueOnError() {
        PipelineSpec spec = renderer.render(
                canvas(List.of(node("n1", "git"),
                        withRun("n9", "echo", PipelineRenderer.CONTINUE, true, PipelineRenderer.WHEN_FAILED)),
                        List.of()), "ws");

        PipelineTask n9 = finallyTask(spec, "n9");
        assertThat(n9.getOnError()).isEqualTo("continue");
        assertThat(n9.getWhen()).hasSize(1);
    }

    @Test
    void anEdgeTouchingAFinallyNodeIsRejected() {
        // 收尾节点**不参与顺序**（Tekton 里它们没有 runAfter、且同时开跑）。
        // 画布上还连着一根线 = 画布在说谎，必须拒掉而不是静默丢掉那根线
        assertThatThrownBy(() -> renderer.render(
                canvas(List.of(node("n1", "git"), withRun("n9", "echo", null, true, null)),
                        List.of(new PipelineCanvas.CanvasEdge("n1", "n9"))), "ws"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("收尾")
                .hasMessageContaining("n9");
    }

    @Test
    void aCanvasMadeOnlyOfFinallyNodesIsRejected() {
        // 全是 finally = tasks 为空 —— Tekton 那边也起不来，报错要报在我们自己嘴里
        assertThatThrownBy(() -> renderer.render(
                canvas(List.of(withRun("n1", "git", null, true, null)), List.of()), "ws"))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("收尾");
    }

    /** 收尾节点（finally）—— 时机见 {@link PipelineRenderer#WHEN_FAILED} 那几个常量 */
    private static PipelineCanvas.CanvasNode withRun(String id, String type, String onError,
                                                     Boolean finallyTask, String finallyWhen) {
        return new PipelineCanvas.CanvasNode(id, type, null, Map.of("repo", "x", "msg", "y"),
                null, onError, finallyTask, finallyWhen);
    }

    private static PipelineTask task(PipelineSpec spec, String name) {
        return spec.getTasks().stream().filter(t -> name.equals(t.getName())).findFirst().orElseThrow();
    }

    private static PipelineTask finallyTask(PipelineSpec spec, String name) {
        return spec.getFinally().stream().filter(t -> name.equals(t.getName())).findFirst().orElseThrow();
    }

    private static WhenExpression onlyWhen(PipelineSpec spec, String name) {
        return finallyTask(spec, name).getWhen().get(0);
    }
}
