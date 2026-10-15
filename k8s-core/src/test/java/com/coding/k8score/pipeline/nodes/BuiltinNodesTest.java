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
    void theTagExpressionGoesStraightAfterTheColonWithNoExtraWrapper() {
        // ⭐ 2026-10-15 第一次真跑之前核形状时抓到：固定 tag 渲染成了
        //    IMAGE='harbor.example.com/proj/app':$('v1')
        //    —— `$(...)` 是 Tekton 的**变量替换**语法（要字面量得写 `$$(`）。
        //    包一个不是变量名的东西，Tekton 替换不了就原样留给 shell，
        //    而 shell 把 `$('v1')` 当成命令替换去执行命令 `v1` —— tag 变空，
        //    buildah 收到 `harbor.example.com/proj/app:` 直接报非法镜像引用。
        //    三种 tag 来源**自己**就是完整的 shell 表达式，冒号后直接拼即可。
        assertThat(scriptOfTag("fixed", "v1"))
                .contains("IMAGE='harbor.example.com/proj/app':'v1'");
        assertThat(scriptOfTag("run-id", ""))
                .contains("IMAGE='harbor.example.com/proj/app':\"$(context.pipelineRun.name)\"");
        assertThat(scriptOfTag("git-sha", ""))
                .contains("IMAGE='harbor.example.com/proj/app':"
                        + "\"$$(cat '/workspace/.git-sha' 2>/dev/null || echo manual)\"");
    }

    /** 同一个镜像节点，只换 tag 来源。 */
    private static String scriptOfTag(String tagSource, String fixedTag) {
        return new ImagePushNode().render(ctxWithConnection("image",
                        Map.of("registryConnectionId", "reg-1", "imageName", "proj/app",
                                "tagSource", tagSource, "fixedTag", fixedTag),
                        "reg-1", "harbor.example.com"))
                .steps().get(0).getScript();
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

    // ---- 代理（2026-10-15：GitHub 的 pack 下载实测卡在 10MiB 不动、Docker Hub 从 Pod 里不通 ——
    //      代理不是优化，是"这个集群能不能拉代码/基础镜像"）----

    @Test
    void theGitNodePutsTheProxyIntoTheStepEnvironment() {
        RenderedStep step = new GitCloneNode().render(ctx("git", Map.of(
                "repo", "https://github.com/g/r.git", "proxy", "http://192.168.85.1:7890")));

        // 两个大小写都写：git/curl 认小写，Go 的 net/http 认大写
        assertThat(envOf(step)).containsEntry("HTTPS_PROXY", "http://192.168.85.1:7890")
                .containsEntry("https_proxy", "http://192.168.85.1:7890");
        // 集群内部的东西（K8s API、内网仓库）不该绕代理
        assertThat(envOf(step).get("NO_PROXY")).contains(".svc").contains("192.168.0.0/16");
    }

    @Test
    void noProxyValueMeansNoProxyEnvAtAll() {
        // 留空 = 直连：**一个变量都不写**（写空值反而会让某些工具把空串当代理地址）
        RenderedStep step = new GitCloneNode().render(ctx("git", Map.of("repo", "https://g/r.git")));

        assertThat(envOf(step)).doesNotContainKeys("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy");
    }

    @Test
    void theImageNodeKeepsItsAuthFileWhenAProxyIsSet() {
        // ⭐ 合并而不是覆盖：那个 env 里本来就有一个 REGISTRY_AUTH_FILE（推送凭据），
        //    加代理时顺手把它冲掉 = 推送 401。这类"顺手覆盖"最容易出，所以钉住。
        RenderedStep step = new ImagePushNode().render(ctxWithConnection("image",
                Map.of("registryConnectionId", "reg-1", "imageName", "app",
                        "proxy", "http://192.168.85.1:7890"),
                "reg-1", "harbor.example.com"));

        assertThat(envOf(step))
                .containsEntry("REGISTRY_AUTH_FILE", "/creds/config.json")
                .containsEntry("HTTPS_PROXY", "http://192.168.85.1:7890");
    }

    @Test
    void theProxyIsAnAdvancedFieldOnBothNodesThatNeedTheNetwork() {
        // 它是"高级"：常态不用填，但排障时得有地方填（面板里收在「高级」下面）
        assertThat(new GitCloneNode().formSchema().fields())
                .filteredOn(NodeField::advanced).extracting(NodeField::key).contains("proxy");
        assertThat(new ImagePushNode().formSchema().fields())
                .filteredOn(NodeField::advanced).extracting(NodeField::key).contains("proxy");
    }

    /** 一个 step 的环境变量（key → value）—— 断言代理这类东西比逐个 find 清楚。 */
    private static Map<String, String> envOf(RenderedStep rendered) {
        return rendered.steps().get(0).getEnv().stream()
                .collect(java.util.stream.Collectors.toMap(
                        io.fabric8.kubernetes.api.model.EnvVar::getName,
                        e -> e.getValue() == null ? "" : e.getValue()));
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
