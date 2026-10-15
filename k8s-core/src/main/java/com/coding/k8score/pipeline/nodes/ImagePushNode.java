package com.coding.k8score.pipeline.nodes;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.pipeline.PipelineConnectionRef;
import com.coding.k8score.pipeline.*;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.tekton.v1.Step;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 构建并推送镜像。**真特殊**的节点之二：Buildah（per-Pod、不用共享构建服务）、
 * 推送凭据由平台注入、产出 digest。
 *
 * <p>⚠️ tag 由**三选一下拉**决定，不发明 {@code ${...}} 模板语法（spec §2.2）。
 * 真正的拼接在组装 {@code PipelineRun} 时做 —— 那里才知道 git sha 与 run 序号。
 */
@Component
public class ImagePushNode implements PipelineNode {

    /** 见 {@link GitCloneNode#WS} —— 与 Tekton 的默认 workspace 挂载点对齐。 */
    static final String WS = "/workspace";

    @Override
    public String type() {
        return "image";
    }

    @Override
    public String label() {
        return "构建并推送镜像";
    }

    @Override
    public String description() {
        return "用 Buildah 构建并推到你选的仓库";
    }

    @Override
    public String icon() {
        return "Upload";
    }

    @Override
    public String accent() {
        return "warning";
    }

    @Override
    public List<String> summaryFields() {
        return List.of("imageName", "tagSource");
    }

    @Override
    public NodeSchema formSchema() {
        return new NodeSchema(List.of(
                NodeField.text("registryConnectionId", "目标仓库（被授权的 registry 连接）", true),
                NodeField.text("imageName", "镜像名（仓库路径，如 proj/app）", true,
                        "只填仓库路径，不要带 registry 地址与 tag —— 那两段由上面的连接和下面的 tag 来源拼出来。\n"
                                + "例：连接是 harbor.example.com、这里填 proj/app、tag 取 git 短 sha\n"
                                + "    → 最终镜像 harbor.example.com/proj/app:a1b2c3d"),
                NodeField.select("tagSource", "tag 来源",
                        List.of(new NodeField.Option("git-sha", "git 短 sha"),
                                new NodeField.Option("run-id", "本次运行序号"),
                                new NodeField.Option("fixed", "自定义固定值")), "git-sha"),
                NodeField.text("fixedTag", "固定 tag（仅「自定义固定值」时用）", false,
                        "只有上面的 tag 来源选「自定义固定值」时才用。\n例：v1.0.0、latest"),
                NodeField.text("dockerfile", "Dockerfile 路径", false,
                        "留空 = 用 workspace 根目录下的 Dockerfile。\n"
                                + "相对「共享 workspace 的根」，可以指向构建上下文之外。\n"
                                + "例：在仓库根 → 留空；在前端目录 → frontend/Dockerfile；"
                                + "统一放一处 → docker/Dockerfile（此时上下文一般留空）"),
                NodeField.text("context", "构建上下文", false,
                        "构建工具能看到的那一个目录 —— 决定 Dockerfile 里的 COPY/ADD 能从哪儿拿文件"
                                + "（与 Dockerfile 放在哪儿无关）。\n"
                                + "留空 = 共享 workspace 的根（git 克隆节点把代码放进来的地方）。\n"
                                + "例：\n"
                                + "  · 仓库根就是构建目录 → 留空\n"
                                + "  · 只有一个前端工程，代码在 frontend/ → 填 frontend\n"
                                + "    （Dockerfile 路径同时要写全：frontend/Dockerfile）\n"
                                + "  · 一个仓库要出多个镜像（frontend/ 与 backend/ 各一份 Dockerfile）\n"
                                + "    → 两个节点分别填 frontend 与 backend\n"
                                + "⚠️ 别把上下文填成含 node_modules/、target/ 的大目录："
                                + "整个目录都要打包传给构建工具，慢且吃盘 —— 用 .dockerignore 排掉"),
                NodeField.advancedText("proxy", "HTTP 代理（高级）",
                        "只在「这个集群拉不到基础镜像 / 推不到目标仓库」时才填；留空 = 直连。\n"
                                + "例：http://192.168.85.1:7890\n"
                                + "buildah 取基础镜像与 push 都会走它；集群内网地址（.svc、内网网段）自动直连。\n"
                                + "⚠️ 不支持带账号密码的代理 —— 那属于凭据，要单独建凭据，别写在这里")));
    }

    @Override
    public RenderedStep render(NodeContext ctx) {
        // ⚠️ 目标镜像 = <被授权连接的 endpoint>/<仓库路径>:<tag>，而 endpoint 只能从连接来。
        // 解析不出就**明确拒渲染** —— 宁可这个节点不可用，也不要产出一个必然失败的脚本
        // （占位符没被替换的 `buildah bud -t __IMAGE_REF__` 是非法镜像引用，一跑就挂）。
        PipelineConnectionRef registry = ctx.connection(ctx.value("registryConnectionId"));
        if (registry == null) {
            throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID,
                    "目标仓库连接不存在或未授权在流水线里使用 —— 无法确定镜像地址");
        }
        String repo = ctx.value("imageName");
        if (repo.isEmpty()) {
            throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID,
                    "镜像名（仓库路径）必填，如 proj/app");
        }
        String dockerfile = ctx.value("dockerfile").isEmpty() ? "Dockerfile" : ctx.value("dockerfile");
        String context = ctx.value("context").isEmpty() ? "." : ctx.value("context");

        String image = GitCloneNode.shellQuote(registry.endpoint() + "/" + repo);
        String tag = tagExpression(ctx.value("tagSource"), ctx.value("fixedTag"));

        // ⚠️ 两处 Tekton 的坑都在这段里：
        //   1. shell 的命令替换要写成 $$(...)，否则 Tekton 会当成自己的变量替换并因解析不到而报错；
        //   2. $(context.pipelineRun.name) 是 Tekton 内建变量，**要它替换**，所以不转义。
        // ⚠️ 而且 tag 表达式**直接拼在冒号后面**，别再套一层 $( )：那种写法只对第 2 条成立，
        //    套在字面量（固定 tag）上会渲染成 `$('v1')` —— Tekton 替换不了就留给 shell，
        //    shell 当命令替换去执行命令 `v1`，tag 变空。
        String script = "set -e\n"
                + "IMAGE=" + image + ":" + tag + "\n"
                + "echo \"目标镜像：$IMAGE\"\n"
                + "buildah bud -f " + GitCloneNode.shellQuote(dockerfile) + " -t \"$IMAGE\" " + GitCloneNode.shellQuote(context) + "\n"
                + "buildah push --digestfile " + GitCloneNode.shellQuote(GitCloneNode.WS + "/.image-digest") + " \"$IMAGE\"\n";

        Step step = new Step();
        step.setName("build-and-push");
        step.setImage("quay.io/buildah/stable:v1.35");
        step.setWorkingDir(WS);
        step.setScript(script);
        // 推送凭据以文件形式挂进来（k8s-server 按 platform-api 给的连接建 Secret）——
        // buildah 认 REGISTRY_AUTH_FILE 这个环境变量
        // ⚠️ 代理是**加进这个列表**，不是替换它：冲掉 REGISTRY_AUTH_FILE = 推送 401
        List<EnvVar> env = new ArrayList<>();
        env.add(new EnvVar("REGISTRY_AUTH_FILE", GitCloneNode.CREDS_MOUNT + "/config.json", null));
        env.addAll(ProxyEnv.of(ctx.value("proxy")));
        step.setEnv(env);
        return new RenderedStep(List.of(step), List.of(GitCloneNode.CREDS_WORKSPACE));
    }

    /**
     * tag 的三种来源 —— 都用**运行时**能算出来的形式表达，因为渲染发生在保存/预览那一刻，
     * 那时还不知道 git sha、也不知道这次的运行名。
     *
     * <p>每个分支返回的都是**完整的** shell 表达式（含引号），调用点直接拼在冒号后面 ——
     * 别在外面再包一层 {@code $( )}（那是 Tekton 的变量替换语法，只对第二个分支成立）。
     */
    private static String tagExpression(String tagSource, String fixedTag) {
        return switch (tagSource) {
            case "fixed" -> GitCloneNode.shellQuote(fixedTag.isEmpty() ? "latest" : fixedTag);
            case "run-id" -> "\"$(context.pipelineRun.name)\"";        // Tekton 内建变量，**不转义**
            default -> "\"$$(cat " + GitCloneNode.shellQuote(GitCloneNode.GIT_SHA_FILE)
                    + " 2>/dev/null || echo manual)\"";               // git 短 sha（克隆节点落的）
        };
    }
}
