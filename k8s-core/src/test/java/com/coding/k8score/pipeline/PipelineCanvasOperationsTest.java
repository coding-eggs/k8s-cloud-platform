package com.coding.k8score.pipeline;

import com.coding.common.exception.CloudPlatformException;
import com.coding.k8score.pipeline.nodes.GitCloneNode;
import com.coding.k8score.pipeline.nodes.ImagePushNode;
import com.coding.k8score.pipeline.nodes.ShellNode;
import com.coding.common.models.pipeline.PipelineConnectionRef;
import com.fasterxml.jackson.core.type.TypeReference;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineCanvasOperationsTest {

    private final PipelineNodeRegistry registry =
            new PipelineNodeRegistry(List.of(new GitCloneNode(), new ShellNode(), new ImagePushNode()));
    private final PipelineCanvasOperations ops =
            new PipelineCanvasOperations(registry, new PipelineRenderer(registry));

    @Test
    void nodeTypesExposeSchemaAndPresetsForTheFrontend() {
        // 前端靠它渲染属性面板 —— 加字段不用改前端，就是靠这条
        List<PipelineNodeTypeView> types = ops.nodeTypes();
        assertThat(types).extracting(PipelineNodeTypeView::type)
                .containsExactlyInAnyOrder("git", "shell", "image");

        PipelineNodeTypeView shell = types.stream()
                .filter(t -> t.type().equals("shell")).findFirst().orElseThrow();
        assertThat(shell.fields()).extracting(NodeField::key)
                .containsExactly("preset", "image", "command", "workDir");
        assertThat(shell.presets()).isNotEmpty();
    }

    @Test
    void anImageFieldIsMarkedSoTheFrontendCanAttachAutocomplete() {
        PipelineNodeTypeView shell = ops.nodeTypes().stream()
                .filter(t -> t.type().equals("shell")).findFirst().orElseThrow();
        assertThat(shell.fields()).filteredOn(f -> f.key().equals("image"))
                .singleElement()
                .satisfies(f -> assertThat(f.type()).isEqualTo(NodeField.FieldType.IMAGE));
    }

    @Test
    void renderYamlContainsBothTaskNamesAndRunAfter() {
        String canvas = """
                {"nodes":[{"id":"clone","type":"git","config":{"repo":"https://g/r.git"}},
                          {"id":"build","type":"shell","config":{"preset":"custom","image":"alpine:3","command":"echo hi"}}],
                 "edges":[{"from":"clone","to":"build"}]}""";
        String yaml = ops.renderYaml(canvas);
        assertThat(yaml).contains("clone").contains("build").contains("runAfter");
    }

    // ---- 内联 taskSpec 的**形状**（2026-10-15 线上回归：第一次真跑流水线被集群拒）----
    @Test
    void theInlineTaskSpecIsFlatSoTektonCanSeeTheSteps() {
        // ⭐ 真跑第一次的报错是 Tekton 的 admission webhook 给的：
        //    missing field(s): spec.pipelineSpec.tasks[0].taskSpec.steps
        //    原因：Tekton v1 的 EmbeddedTask 把 TaskSpec **内联**在自己身上
        //    （`TaskSpec `json:",inline"``），它自己那个 `spec` 字段是给**自定义任务**留的
        //    RawExtension（见 tektoncd/pipeline pkg/apis/pipeline/v1/pipeline_types.go）。
        //    我们把 TaskSpec 塞进了那个 spec → 内联的那一份是空的 → 校验器看不见 steps。
        //
        //    这条测试盯的是**序列化出来的形状**（yaml 再解析回 map，与集群看到的同一份），
        //    不是 Java 对象图：老测试只断言 YAML 里出现过 "clone"/"build"，
        //    对象摆错位置也照样绿 —— 这正是这个 bug 能活到线上的原因。
        String canvas = """
                {"nodes":[{"id":"clone","type":"git","config":{"repo":"https://g/r.git"}}],"edges":[]}""";

        String yaml = ops.renderYaml(canvas);
        Map<String, Object> taskSpec = taskSpecOfFirstTask(yaml);

        assertThat(taskSpec).as("渲染出来的 YAML：\n%s", yaml).containsKey("steps");
        assertThat(taskSpec).as("那个壳是「自定义任务」的东西，不是我们").doesNotContainKey("spec");
        assertThat(maps(taskSpec.get("steps")))
                .singleElement()
                .satisfies(step -> assertThat(step).containsKey("script"));
        // 隐式共享 workspace 的声明也要一起被内联（否则 task 里的 workspace 挂不上）
        assertThat(maps(taskSpec.get("workspaces")))
                .extracting(w -> w.get("name"))
                .containsExactly(PipelineCanvasOperations.WORKSPACE);
    }

    @Test
    void theLayoutCoordinatesDoNotChangeWhatRuns() {
        // ⭐ 坐标是**纯展示**：同一份画布，带不带 position 必须渲染出逐字节相同的 YAML。
        //    这条是「往画布里加布局字段」的安全带 —— 哪天有人把坐标喂进渲染，它会红。
        String plain = """
                {"nodes":[{"id":"clone","type":"git","config":{"repo":"https://g/r.git"}},
                          {"id":"build","type":"shell","config":{"image":"alpine:3.20","command":"echo hi"}}],
                 "edges":[{"from":"clone","to":"build"}]}""";
        String withPositions = """
                {"nodes":[{"id":"clone","type":"git","config":{"repo":"https://g/r.git"},
                             "position":{"x":123,"y":45}},
                          {"id":"build","type":"shell","config":{"image":"alpine:3.20","command":"echo hi"},
                             "position":{"x":400,"y":300}}],
                 "edges":[{"from":"clone","to":"build"}]}""";

        assertThat(ops.renderYaml(withPositions)).isEqualTo(ops.renderYaml(plain));
    }

    @Test
    void theSharedWorkspaceIsMountedAtSlashWorkspaceNotAtItsDefault() {
        // ⭐ 第三次真跑（2026-10-15）：git 节点报
        //    fatal: destination path '.' already exists and is not an empty directory.
        //    真因不在 git —— Pod 的 volumeMounts 是：
        //      /workspace/ws   ← 我们的共享 workspace（**没写 mountPath 时 Tekton 的默认值**）
        //      /workspace      ← Tekton 自己的空目录（tekton-internal-workspace）
        //    三个后果：① /workspace 不为空 → clone 拒；② 克隆与 .git-sha 全落进那个空目录，
        //    **根本没进 PVC** → 节点之间零共享（「共享 workspace」整个没生效）；
        //    ③ 下游构建节点读 /workspace/.git-sha 必然扑空。
        //    所以这个挂载点必须**显式**写出来，不能依赖默认值。
        String canvas = """
                {"nodes":[{"id":"clone","type":"git","config":{"repo":"https://g/r.git",
                   "connectionId":"git-1"}}],"edges":[]}""";

        String yaml = ops.renderYaml(canvas, Map.of(
                "git-1", new PipelineConnectionRef("git-1", "https://g", null, null)));

        assertThat(maps(taskSpecOfFirstTask(yaml).get("workspaces")))
                .as("渲染出来的 YAML：\n%s", yaml)
                .extracting(w -> w.get("mountPath"))
                .containsExactly("/workspace", "/creds");
    }

    @Test
    void everyTaskBindsItsWorkspacesNotJustDeclaresThem() {
        // ⭐ 第二次真跑的失败（集群 events 里抓到的原话）：
        //    Warning Failed taskrun/…-git-quo31
        //    [User error] declared workspace "ws" is required but has not been bound
        //    Tekton 只会把**脚本里用 $(workspaces.X.path) 引用过**的 workspace 自动传播给 TaskRun
        //    —— pkg/workspace/apply.go 的 FindWorkspacesUsedByTask 是照着替换变量扫脚本的。
        //    我们的节点脚本用的是字面量 /workspace、/creds，一个都不会被传播 → TaskRun 拿到
        //    0 个绑定 → pkg/workspace/validate.go 直接拒（1 秒内失败，Pod 都没起来，
        //    所以连日志都没有）。
        //
        //    「声明」（taskSpec.workspaces：这个 task 需要哪些 workspace）与
        //    「绑定」（tasks[].workspaces：这些 workspace 接到 pipeline 的哪一个上）是两件事。
        String canvas = """
                {"nodes":[{"id":"clone","type":"git","config":{"repo":"https://g/r.git",
                   "connectionId":"git-1"}}],"edges":[]}""";

        String yaml = ops.renderYaml(canvas, Map.of(
                "git-1", new PipelineConnectionRef("git-1", "https://g", null, null)));

        // 同名绑定：只写 name，Tekton 就把它接到 pipeline 里同名的那个 workspace
        // （pipelinerun.go 的 getTaskrunWorkspaces：workspace 字段为空时用 name）
        assertThat(maps(firstTaskOf(yaml).get("workspaces")))
                .as("渲染出来的 YAML：\n%s", yaml)
                .extracting(w -> w.get("name"))
                .containsExactly(PipelineCanvasOperations.WORKSPACE, "creds");
        // 声明那一半也还在（两者缺一不可）
        assertThat(maps(taskSpecOfFirstTask(yaml).get("workspaces")))
                .extracting(w -> w.get("name"))
                .containsExactly(PipelineCanvasOperations.WORKSPACE, "creds");
    }

    /** 渲染出的 YAML → 第一条 task 子树（**重新解析**，与集群看到的同一份形状）。 */
    private static Map<String, Object> firstTaskOf(String yaml) {
        try {
            Map<String, Object> spec =
                    Serialization.yamlMapper().readValue(yaml, new TypeReference<Map<String, Object>>() {});
            return maps(spec.get("tasks")).get(0);
        } catch (Exception e) {
            throw new AssertionError("渲染出来的 YAML 解析不了：\n" + yaml, e);
        }
    }

    /** 渲染出的 YAML → 第一条 task 的 taskSpec 子树。 */
    private static Map<String, Object> taskSpecOfFirstTask(String yaml) {
        Map<String, Object> task = firstTaskOf(yaml);
        return task.get("taskSpec") == null ? Map.of() : asMap(task.get("taskSpec"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object o) {
        return o == null ? List.of() : (List<Map<String, Object>>) o;
    }

    @Test
    void renderRejectsACanvasWithACycle() {
        // ⭐ Review Focus #1 的另一半：前端实时提示之外，**后端也必须拒**
        String canvas = """
                {"nodes":[{"id":"a","type":"git","config":{"repo":"r"}},
                          {"id":"b","type":"shell","config":{"command":"c"}}],
                 "edges":[{"from":"a","to":"b"},{"from":"b","to":"a"}]}""";
        assertThatThrownBy(() -> ops.render(canvas))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("环");
    }

    @Test
    void renderRejectsAnUnknownNodeType() {
        String canvas = """
                {"nodes":[{"id":"a","type":"nope","config":{}}],"edges":[]}""";
        assertThatThrownBy(() -> ops.render(canvas))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("nope");
    }

    // ---- 带镜像节点的画布：连接是渲染的必需品（2026-10-15 的回归）----

    @Test
    void anImageNodeRendersOnceItsConnectionIsSupplied() {
        // ⭐ 回归：目标镜像地址 = 连接 endpoint + 仓库路径 + tag —— 没有连接就没有地址。
        //    预览/保存那条路径当初**不带连接**，于是「保存一条含构建节点的流水线」必然报
        //    「目标仓库连接不存在或未授权在流水线里使用」。修法就是这条路径也把连接交上去
        //    （endpoint-only，不含凭据内容）。
        String canvas = """
                {"nodes":[{"id":"build","type":"image","config":{"registryConnectionId":"reg-1",
                  "imageName":"proj/app","tagSource":"fixed","fixedTag":"v1"}}],"edges":[]}""";

        String yaml = ops.renderYaml(canvas,
                Map.of("reg-1", new PipelineConnectionRef("reg-1", "harbor.example.com", null, null)));

        // 断言到「地址拼出来了」为止：tag 那一段被 shellQuote 成了 'v1'（引号是为了防注入）
        assertThat(yaml).contains("harbor.example.com/proj/app").contains("buildah push");
    }

    @Test
    void anImageNodeStillRefusesToRenderWithoutItsConnection() {
        // 没连接就**拒渲染**这条行为没变：宁可不出来，也不产出一个必然失败的镜像引用
        String canvas = """
                {"nodes":[{"id":"build","type":"image","config":{"registryConnectionId":"reg-1",
                  "imageName":"proj/app"}}],"edges":[]}""";

        assertThatThrownBy(() -> ops.renderYaml(canvas))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("未授权");
    }
}
