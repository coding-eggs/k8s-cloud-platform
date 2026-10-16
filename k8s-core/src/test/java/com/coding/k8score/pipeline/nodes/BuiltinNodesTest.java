package com.coding.k8score.pipeline.nodes;

import com.coding.common.exception.CloudPlatformException;
import com.coding.k8score.pipeline.*;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
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
        List<PipelineNode> all = AllNodes.list();
        assertThat(all).extracting(PipelineNode::type)
                .containsExactlyInAnyOrder("git", "shell", "image", "java", "node", "go", "python");
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
        // ⚠️ 「命令」节点**有意不带**预设构建节点那两行「工作目录/目录内容」的自白：
        //    它的合同是"脚本你自己写，平台一个字都不加"（与 presetBuildNodesSayWhereTheyAreRunning 成对）
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
    void presetBuildNodesSayWhereTheyAreRunning() {
        // ⭐ 2026-10-16 真跑撞到：工作目录**填多了一层仓库名** → kubelet 把不存在的目录 mkdir 出来 →
        //    命令在一个空目录里跑，报出来的是「npm ci 没有 package-lock.json」
        //    「/tekton/scripts/script-0-xxx: 3: -B: not found」这类与路径毫不相干的错。
        //    所以预设构建节点的脚本最前面必须自报「我在哪、这儿有什么」。
        RenderedStep step = new NodeBuildNode().render(ctx("node",
                Map.of("image", "", "command", "npm ci && npm run build", "workDir", "platform-web")));

        String script = step.steps().get(0).getScript();
        assertThat(script).startsWith("echo \"工作目录：$(pwd)\"\nls -A | head -20\n");
        assertThat(script).endsWith("npm ci && npm run build");   // 用户填的命令原样还在最后
        assertThat(step.steps().get(0).getWorkingDir()).isEqualTo("/workspace/platform-web");
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
        // ⭐ 2026-10-16 真跑撞到：`$$( )` 那套"转义 shell 命令替换"的写法在这一版 Tekton 上
        //    **不还原** —— shell 把 `$$` 当进程号展开，tag 变成 `15(cat '/workspace/.git-sha' …)`，
        //    buildah 报 invalid reference format。现在**一个命令替换都不用**（read 进普通变量）。
        assertThat(script).doesNotContain("$$");
        assertThat(script).contains("read -r TAG < '/workspace/.git-sha'");
        // 凭据以文件形式挂进来，不是写进脚本（挂载点也必须显式给上 —— 见 WorkspaceRef）
        assertThat(step.workspaces()).extracting(RenderedStep.WorkspaceRef::name).contains("creds");
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
        //
        // ⭐ 2026-10-16 真跑又抓到**另一面**：`$$(` 那个转义在这一版 Tekton 上**不还原**，
        //    shell 把 `$$` 当**进程号**展开 → `.../platform-web:15(cat '/workspace/.git-sha' …)`。
        //    所以现在三种来源都先落进一个**普通 shell 变量 TAG**（`$TAG`/`$IMAGE` 这种普通变量
        //    Tekton 从来不碰），再用 `"$TAG"` 拼进 IMAGE —— 一个命令替换都不需要。
        assertThat(scriptOfTag("fixed", "v1"))
                .contains("TAG='v1'\nIMAGE='harbor.example.com/proj/app':\"$TAG\"");
        assertThat(scriptOfTag("run-id", ""))
                .contains("TAG=\"$(context.pipelineRun.name)\"\nIMAGE='harbor.example.com/proj/app':\"$TAG\"");
        assertThat(scriptOfTag("git-sha", ""))
                .contains("read -r TAG < '/workspace/.git-sha'")
                .contains("IMAGE='harbor.example.com/proj/app':\"$TAG\"");
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
        assertThat(step.workspaces()).extracting(RenderedStep.WorkspaceRef::name).contains("creds");
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
    void theImageNodeNeverDependsOnFuse() {
        // ⭐ 2026-10-16 真跑撞到：构建 Pod 里**没有 /dev/fuse**。
        //    k8s 给每个容器的是最小 /dev（只有 null/zero/full/tty/random/urandom —— 实机 ls 过），
        //    而 buildah 镜像默认的 overlay 驱动挂的是 /usr/bin/fuse-overlayfs：
        //      fuse: device not found, try 'modprobe fuse' first
        //      fuse-overlayfs: cannot mount: No such file or directory
        //    → Error: mounting new container: … exit status 1（整步挂掉）
        //    内核 overlay 驱动同样不行：容器默认能力集里**没有 CAP_SYS_ADMIN**，mount 会 EPERM。
        //    剩下的路只有"压根不挂载"：vfs 存储驱动（纯目录拷贝）+ chroot 隔离（不起 runc、不建命名空间）。
        //    集群侧一行都不用改 —— 这也是选它而不是"加 privileged"的理由。
        RenderedStep step = new ImagePushNode().render(ctxWithConnection("image",
                Map.of("registryConnectionId", "reg-1", "imageName", "proj/app"),
                "reg-1", "harbor.example.com"));

        assertThat(envOf(step))
                .containsEntry("STORAGE_DRIVER", "vfs")
                .containsEntry("BUILDAH_ISOLATION", "chroot");
    }

    @Test
    void theProxyIsAnAdvancedFieldOnBothNodesThatNeedTheNetwork() {
        // 它是"高级"：常态不用填，但排障时得有地方填（面板里收在「高级」下面）
        assertThat(new GitCloneNode().formSchema().fields())
                .filteredOn(NodeField::advanced).extracting(NodeField::key).contains("proxy");
        assertThat(new ImagePushNode().formSchema().fields())
                .filteredOn(NodeField::advanced).extracting(NodeField::key).contains("proxy");
    }

    // ---- 依赖缓存（2026-10-15：shell 节点的「复用依赖缓存」开关）----

    @Test
    void theShellNodePointsEveryToolAtTheCacheWhenTheToggleIsOn() {
        RenderedStep step = new ShellNode().render(ctx("shell", Map.of(
                "image", "maven:3.9-eclipse-temurin-21", "command", "mvn -B package", "cache", "true")));

        // 一次给全套：节点并不知道这一步会跑 mvn 还是 npm，而**没跑的工具会忽略自己那几个变量**
        assertThat(envOf(step)).containsEntry("MAVEN_OPTS", "-Dmaven.repo.local=/cache/m2")
                .containsEntry("npm_config_cache", "/cache/npm")
                .containsEntry("GOMODCACHE", "/cache/go-mod")
                .containsEntry("PIP_CACHE_DIR", "/cache/pip");
        // 还要把 /cache 挂上（挂的是什么卷由绑定层决定：长期卷或退化的空目录）
        assertThat(step.workspaces()).singleElement()
                .satisfies(w -> {
                    assertThat(w.name()).isEqualTo("cache");
                    assertThat(w.mountPath()).isEqualTo("/cache");
                });
    }

    @Test
    void theShellNodeStaysBareWhenTheCacheToggleIsOff() {
        // 默认（没开）不能有任何副作用：不挂卷、不写环境变量 —— 老画布渲染结果逐字节不变
        RenderedStep step = new ShellNode().render(ctx("shell",
                Map.of("image", "alpine:3.20", "command", "echo hi")));

        assertThat(step.workspaces()).isEmpty();
        assertThat(step.steps().get(0).getEnv()).isEmpty();
    }

    // ---- 预设构建节点（java / node / go / python，2026-10-15）----

    @Test
    void everyBuildNodeWorksOutOfTheBoxAndCarriesTheCache() {
        // 预设节点的意义就是「开箱能用」：镜像与命令都留空也要渲染出能跑的东西，
        // 而且**默认带依赖缓存** —— 用户不需要知道 /cache 的存在，这就是相对「命令」节点的价值
        for (PipelineNode node : List.of(new JavaBuildNode(), new NodeBuildNode(),
                new GoBuildNode(), new PythonBuildNode())) {
            RenderedStep step = node.render(ctx(node.type(), Map.of()));

            assertThat(step.steps().get(0).getImage()).as(node.type() + " 的默认镜像").isNotBlank();
            assertThat(step.steps().get(0).getScript()).as(node.type() + " 的默认命令").isNotBlank();
            assertThat(step.steps().get(0).getWorkingDir()).as(node.type() + " 的工作目录")
                    .isEqualTo("/workspace");
            // 一次给全套缓存变量：节点不知道用户会怎么改命令，没跑到的工具会忽略自己那几个
            assertThat(envOf(step)).as(node.type() + " 的缓存变量")
                    .containsKeys("MAVEN_OPTS", "npm_config_cache", "GOMODCACHE", "PIP_CACHE_DIR");
            assertThat(step.workspaces()).as(node.type() + " 的缓存 workspace").singleElement()
                    .satisfies(w -> {
                        assertThat(w.name()).isEqualTo("cache");
                        assertThat(w.mountPath()).isEqualTo("/cache");
                    });
        }
    }

    // ---- 资源：构建 Pod 的容器上限（用户 2026-10-16：构建 OOM 了，要能多要点内存）----
    //
    // 形状由集群侧两条约束定死：
    //   1. Tekton 的 `default-container-resource-requirements` 只在容器**完全没写** resources 时才填
    //      → 我们一旦要写，就**四个字段一起写**（requests.cpu/memory + limits.cpu/memory）。
    //      少写的那一项会被命名空间配额拒：`must specify limits.cpu for: step-xxx`；
    //   2. requests 那档必须**小**：命名空间配额的 requests 档通常远小于 limits 档
    //      （真集群：requests 4 CPU / 4Gi vs limits 8 CPU / 8Gi）—— 把 requests 跟着用户填的数放大就会撞它。

    @Test
    void buildNodesWriteNoResourcesUnlessAskedTo() {
        // 留空 = 一个字都不写（集群那套默认照旧生效）—— 老画布渲染出来与本功能之前逐字节一致
        for (PipelineNode node : List.of(new JavaBuildNode(), new NodeBuildNode(),
                new GoBuildNode(), new PythonBuildNode())) {
            RenderedStep step = node.render(ctx(node.type(), Map.of()));

            assertThat(step.steps().get(0).getComputeResources()).as(node.type() + " 没填就该什么都不写")
                    .isNull();
        }
    }

    @Test
    void aMemoryLimitIsWrittenAsACompleteResourceBlock() {
        RenderedStep step = new NodeBuildNode().render(ctx("node",
                Map.of("command", "npm ci && npm run build-only", "memory", "2048")));

        ResourceRequirements res = step.steps().get(0).getComputeResources();
        assertThat(res).isNotNull();
        // 四个字段一个都不能少（少一个就被配额校验拒，见上面那段）
        assertThat(res.getRequests()).containsOnlyKeys("cpu", "memory");
        assertThat(res.getLimits()).containsOnlyKeys("cpu", "memory");
        assertThat(res.getRequests().get("memory")).isEqualTo(Quantity.parse("128Mi"));
        assertThat(res.getRequests().get("cpu")).isEqualTo(Quantity.parse("100m"));
        // ⭐ 用户只填数字，单位是平台补的 Mi
        assertThat(res.getLimits().get("memory")).isEqualTo(Quantity.parse("2048Mi"));
        assertThat(res.getLimits().get("cpu")).isEqualTo(Quantity.parse("1"));
    }

    @Test
    void aMemoryLimitThatIsNotANumberIsRejected() {
        // 字段是「只填数字、单位固定 Mi」—— 用户仍可能绕过前端直接存画布（`2Gi` 就是典型的手写值）
        assertThatThrownBy(() -> new NodeBuildNode().render(ctx("node", Map.of("memory", "2Gi"))))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("只能填数字");
    }

    @Test
    void aMemoryLimitBelowTheRequestFloorIsRejected() {
        // requests.memory 是 128Mi（那档必须小）—— 上限比它还小的话 Pod 会被 K8s 拒，
        // 报出来的是另一句看不懂的话，所以在这里先拦
        assertThatThrownBy(() -> new NodeBuildNode().render(ctx("node", Map.of("memory", "64"))))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("128");
    }

    @Test
    void everyBuildNodeOffersTheMemoryFieldWithAFixedUnit() {
        // 字段清单是四个节点共用的（BuildStep.schema）—— 这条保证没人改漏。
        // ⚠️ 顺带钉住**前端合同**：类型是 NUMBER、单位由 schema 给（用户不用写单位）
        for (PipelineNode node : List.of(new JavaBuildNode(), new NodeBuildNode(),
                new GoBuildNode(), new PythonBuildNode())) {
            NodeField f = node.formSchema().fields().stream()
                    .filter(x -> "memory".equals(x.key())).findFirst()
                    .orElseThrow(() -> new AssertionError(node.type() + " 少了 memory 字段"));
            assertThat(f.type()).as(node.type()).isEqualTo(NodeField.FieldType.NUMBER);
            assertThat(f.unit()).as(node.type()).isEqualTo("Mi");
        }
    }

    @Test
    void everyBuildNodesCommandFieldAsksForTheWholeCommand() {
        // ⭐ 2026-10-16 用户被这个字段误导：Java 节点上它**曾叫「Maven 参数」**，
        //    于是他只填了 `clean package -DskipTests`（没有 mvn）→ 平台把它当脚本跑 →
        //    `/tekton/scripts/script-0-xxx: 5: clean: not found`，节点失败。
        //    这里钉住三件事：四个节点**同名**、名字说的是「命令」、说明里点明要**完整**的。
        for (PipelineNode node : List.of(new JavaBuildNode(), new NodeBuildNode(),
                new GoBuildNode(), new PythonBuildNode())) {
            NodeField f = node.formSchema().fields().stream()
                    .filter(x -> "command".equals(x.key())).findFirst()
                    .orElseThrow(() -> new AssertionError(node.type() + " 少了 command 字段"));

            assertThat(f.label()).as(node.type() + " 的字段名").isEqualTo("构建命令");
            assertThat(f.help()).as(node.type() + " 的说明").contains("完整");
        }
    }

    @Test
    void aFilledFieldWinsOverTheBuildNodesDefault() {
        // 与 shell 节点的预设同一条规矩：默认值只是默认值
        RenderedStep step = new JavaBuildNode().render(ctx("java", Map.of(
                "image", "harbor.example.com/base/maven:3.9",
                "command", "mvn -B clean verify",
                "workDir", "backend")));

        assertThat(step.steps().get(0).getImage()).isEqualTo("harbor.example.com/base/maven:3.9");
        // 命令原样落在脚本最后 —— 前面那两行是平台自报的「工作目录 / 目录内容」
        // （见 presetBuildNodesSayWhereTheyAreRunning）
        assertThat(step.steps().get(0).getScript()).endsWith("mvn -B clean verify");
        assertThat(step.steps().get(0).getWorkingDir()).isEqualTo("/workspace/backend");
    }

    @Test
    void theGoNodeDefaultsToAReachableModuleProxy() {
        // ⭐ 这个默认值不是美化：proxy.golang.org 从 Pod 里连不通（2026-10-15 实测，
        //    解析到 Google 的 IP 后连不上），而 Maven Central / npm / PyPI 都通 ——
        //    所以 Go 是唯一一个"默认值决定能不能拉到依赖"的节点
        assertThat(envOf(new GoBuildNode().render(ctx("go", Map.of()))))
                .containsEntry("GOPROXY", GoBuildNode.DEFAULT_GOPROXY);

        assertThat(envOf(new GoBuildNode().render(ctx("go", Map.of("goproxy", "http://athens.internal")))))
                .containsEntry("GOPROXY", "http://athens.internal");
    }

    @Test
    void theBuildNodesCarryTheProxyToo() {
        // 代理是三个联网节点的公共能力（git / 镜像 / 构建），键名都叫 proxy
        assertThat(envOf(new JavaBuildNode().render(ctx("java", Map.of("proxy", "http://p:7890")))))
                .containsEntry("HTTPS_PROXY", "http://p:7890");
    }

    @Test
    void everyBuildNodeKeepsTheProxyAndToolSpecificKnobsInTheAdvancedSection() {
        // 摊在面板上的只该是"每次都要想一下"的字段；代理、GOPROXY、pip 源都收进「高级」
        for (PipelineNode node : List.of(new JavaBuildNode(), new NodeBuildNode(),
                new GoBuildNode(), new PythonBuildNode())) {
            assertThat(node.formSchema().fields()).as(node.type())
                    .filteredOn(NodeField::advanced).extracting(NodeField::key).contains("proxy");
        }
        assertThat(new GoBuildNode().formSchema().fields())
                .filteredOn(NodeField::advanced).extracting(NodeField::key).contains("goproxy");
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
