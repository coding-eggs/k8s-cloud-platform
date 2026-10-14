package com.coding.k8score.pipeline.nodes;

import com.coding.k8score.pipeline.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltinNodesTest {

    private static NodeContext ctx(String type, Map<String, String> config) {
        return new NodeContext("n1", "n1", "ws", new PipelineCanvas.CanvasNode("n1", type, config));
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
    void imageNodeRendersABuildahStep() {
        RenderedStep step = new ImagePushNode().render(ctx("image",
                Map.of("imageName", "app", "tagSource", "git-sha", "dockerfile", "Dockerfile", "context", ".")));
        assertThat(step.steps()).hasSize(1);
        assertThat(step.steps().get(0).getImage()).contains("buildah");
        assertThat(step.steps().get(0).getScript()).contains("buildah");
    }
}
