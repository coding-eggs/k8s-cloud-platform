package com.coding.k8score.pipeline.nodes;

import com.coding.k8score.pipeline.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuiltinNodesTest {

    private static NodeContext ctx(String type, Map<String, String> config) {
        return new NodeContext("n1", "n1", "ws", new PipelineCanvas.CanvasNode("n1", type, null, config));
    }

    /** 带一条已授权连接（渲染时才有 —— 预览路径不带凭据）。 */
    private static NodeContext ctxWithConnection(String type, Map<String, String> config,
                                                 String id, String endpoint) {
        return new NodeContext("n1", "n1", "ws",
                new PipelineCanvas.CanvasNode("n1", type, null, config),
                Map.of(id, new com.coding.common.models.pipeline.PipelineConnectionRef(
                        id, endpoint, "u", "p")));
    }

    @Test
    void allThreeNodeTypesAreRegisteredAndTheirSchemasAreWellFormed() {
        // 护栏（spec §2.4）：type 非空、schema 非空、字段 key 不重复
        List<PipelineNode> all = List.of(new GitCloneNode(), new ShellNode(), new ImagePushNode());
        assertThat(all).extracting(PipelineNode::type).containsExactlyInAnyOrder("git", "shell", "image");
        for (PipelineNode n : all) {
            assertThat(n.formSchema().fields()).as(n.type() + " 的 schema").isNotEmpty();
            assertThat(n.formSchema().fields()).extracting(NodeField::key).doesNotHaveDuplicates();
        }
    }

    @Test
    void gitNodeRendersACloneStepIntoTheSharedWorkspace() {
        RenderedStep step = new GitCloneNode().render(ctx("git",
                Map.of("repo", "https://gitlab.example.com/g/r.git", "revision", "main", "dir", "src")));

        assertThat(step.steps()).hasSize(1);
        assertThat(step.steps().get(0).getScript()).contains("git");
        assertThat(step.steps().get(0).getScript()).contains("https://gitlab.example.com/g/r.git");
        // 源码写进共享 workspace —— 下游节点不用做任何事就能看到
        assertThat(step.steps().get(0).getWorkingDir()).startsWith("/workspace");
    }

    @Test
    void shellNodeDefaultDirIsTheWorkspaceRoot() {
        RenderedStep step = new ShellNode().render(ctx("shell",
                Map.of("image", "alpine:3", "command", "echo hi", "workDir", "")));
        assertThat(step.steps().get(0).getWorkingDir()).isEqualTo("/workspace");
    }

    @Test
    void shellNodeSplitsTheCommandOnNewlines() {
        RenderedStep step = new ShellNode().render(ctx("shell",
                Map.of("image", "alpine:3", "command", "echo a\necho b", "workDir", ".")));
        assertThat(step.steps().get(0).getScript()).isEqualTo("echo a\necho b");
    }

    @Test
    void shellNodeUsesThePresetDefaultsWhenFieldsAreEmpty() {
        // 选了 java-build 预设但没改任何字段 → 用的是预设的镜像与命令
        RenderedStep step = new ShellNode().render(ctx("shell",
                Map.of("preset", "java-build", "image", "", "command", "", "workDir", "")));
        assertThat(step.steps().get(0).getImage()).isEqualTo("maven:3.9-eclipse-temurin-21");
        assertThat(step.steps().get(0).getScript()).contains("mvn");
    }

    @Test
    void anOverriddenImageWinsOverThePreset() {
        // ⭐ 用户明确要求：预设只是默认值，改了就得生效
        RenderedStep step = new ShellNode().render(ctx("shell",
                Map.of("preset", "java-build", "image", "my-harbor.example.com/base/maven:3.9",
                        "command", "mvn -B verify", "workDir", ".")));
        assertThat(step.steps().get(0).getImage()).isEqualTo("my-harbor.example.com/base/maven:3.9");
        assertThat(step.steps().get(0).getScript()).isEqualTo("mvn -B verify");
    }

    @Test
    void everyPresetCarriesItsOwnImageAndCommand() {
        assertThat(BuildPreset.all()).isNotEmpty();
        for (BuildPreset p : BuildPreset.all()) {
            if ("custom".equals(p.key())) {
                continue;
            }
            assertThat(p.image()).as(p.key() + " 的镜像").isNotBlank();
            assertThat(p.command()).as(p.key() + " 的命令").isNotBlank();
        }
    }

    @Test
    void imageNodeTagSourceIsAThreeWayChoiceNotATemplate() {
        // spec §2.2：tag 由三选一下拉决定，**不发明 ${...} 模板语法**
        NodeSchema schema = new ImagePushNode().formSchema();
        NodeField tagSource = schema.fields().stream()
                .filter(f -> f.key().equals("tagSource")).findFirst().orElseThrow();
        assertThat(tagSource.options()).extracting(NodeField.Option::value)
                .containsExactlyInAnyOrder("git-sha", "run-id", "fixed");
        assertThat(schema.fields()).extracting(NodeField::key).doesNotContain("tagTemplate");
    }

    @Test
    void imageNodeRendersABuildahStepWithTheResolvedImageReference() {
        RenderedStep step = new ImagePushNode().render(ctxWithConnection("image",
                Map.of("registryConnectionId", "reg-1", "imageName", "proj/app",
                        "tagSource", "git-sha", "dockerfile", "Dockerfile", "context", "."),
                "reg-1", "harbor.example.com"));

        String script = step.steps().get(0).getScript();
        assertThat(step.steps().get(0).getImage()).contains("buildah");
        assertThat(script).contains("buildah");
        // 目标地址来自**被授权连接的 endpoint**，不是用户输入 —— 用户只提供仓库路径
        assertThat(script).contains("harbor.example.com/proj/app");
        // ⚠️ 再也不该有没被替换的占位符（那会让 buildah 收到一个非法镜像引用，一跑就挂）
        assertThat(script).doesNotContain("__IMAGE_REF__");
        // shell 的命令替换必须写成 $$(...)，否则 Tekton 会当自己的变量替换并因解析不到而报错
        assertThat(script).contains("$$(cat");
        // 凭据以文件形式挂进来，不是写进脚本
        assertThat(step.extraWorkspaces()).contains("creds");
        assertThat(step.steps().get(0).getEnv())
                .anySatisfy(e -> assertThat(e.getName()).isEqualTo("REGISTRY_AUTH_FILE"));
    }

    @Test
    void imageNodeRefusesToRenderWithoutAResolvedConnection() {
        // ⭐ 宁可这个节点不可用，也不要产出一个必然失败的脚本
        assertThatThrownBy(() -> new ImagePushNode().render(ctx("image",
                Map.of("registryConnectionId", "reg-x", "imageName", "app"))))
                .isInstanceOf(com.coding.common.exception.CloudPlatformException.class)
                .hasMessageContaining("未授权");
    }

    @Test
    void theRunIdTagUsesTektonsBuiltInVariableUnescaped() {
        RenderedStep step = new ImagePushNode().render(ctxWithConnection("image",
                Map.of("registryConnectionId", "reg-1", "imageName", "app", "tagSource", "run-id"),
                "reg-1", "harbor.example.com"));

        // $(context.pipelineRun.name) **要** Tekton 替换，所以不能转义成 $$(...)
        assertThat(step.steps().get(0).getScript()).contains("$(context.pipelineRun.name)");
    }

    @Test
    void theGitNodeCapturesTheShortShaAndUsesTheMountedCredentials() {
        RenderedStep step = new GitCloneNode().render(ctxWithConnection("git",
                Map.of("connectionId", "git-1", "repo", "https://g/r.git"), "git-1", "https://g"));

        assertThat(step.steps().get(0).getScript()).contains("rev-parse --short HEAD");
        assertThat(step.steps().get(0).getScript()).contains(".netrc");
        assertThat(step.extraWorkspaces()).contains("creds");
    }

    /**
     * ⚠️ **跨模块契约的护栏**：platform-api 会照这两个字段名扫画布、逐条问「这条连接被授权了吗」
     * （spec §5.3 / Review Focus #4）。它读不到 k8s-core 的类型，所以字段名就是契约 ——
     * 改名必须同时改 {@code PipelineRunService.CONNECTION_KEYS}，这条测试是唯一的提醒。
     */
    @Test
    void theConnectionReferenceFieldsPlatformApiScansForAreStillNamedThis() {
        assertThat(new GitCloneNode().formSchema().fields())
                .extracting(NodeField::key).contains("connectionId");
        assertThat(new ImagePushNode().formSchema().fields())
                .extracting(NodeField::key).contains("registryConnectionId");
    }
}
