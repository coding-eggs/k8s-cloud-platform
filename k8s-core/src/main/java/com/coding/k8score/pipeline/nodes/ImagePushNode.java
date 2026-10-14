package com.coding.k8score.pipeline.nodes;

import com.coding.k8score.pipeline.*;
import io.fabric8.tekton.v1.Step;
import org.springframework.stereotype.Component;

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

    /** 组装 {@code PipelineRun} 时用它做替换 —— 占位符只有这一处，找得到。 */
    public static final String IMAGE_REF_PLACEHOLDER = "__IMAGE_REF__";

    @Override
    public String type() {
        return "image";
    }

    @Override
    public NodeSchema formSchema() {
        return new NodeSchema(List.of(
                NodeField.text("registryConnectionId", "目标仓库（被授权的 registry 连接）", true),
                NodeField.text("imageName", "镜像名（仓库路径，如 proj/app）", true),
                NodeField.select("tagSource", "tag 来源",
                        List.of(new NodeField.Option("git-sha", "git 短 sha"),
                                new NodeField.Option("run-id", "本次运行序号"),
                                new NodeField.Option("fixed", "自定义固定值")), "git-sha"),
                NodeField.text("fixedTag", "固定 tag（仅「自定义固定值」时用）", false),
                NodeField.text("dockerfile", "Dockerfile 路径", false),
                NodeField.text("context", "构建上下文", false)));
    }

    @Override
    public RenderedStep render(NodeContext ctx) {
        String dockerfile = ctx.value("dockerfile").isEmpty() ? "Dockerfile" : ctx.value("dockerfile");
        String context = ctx.value("context").isEmpty() ? "." : ctx.value("context");

        // ⚠️ 这里用占位符 IMAGE_REF_PLACEHOLDER，由组装 PipelineRun 的那一层替换成
        // `endpoint/仓库路径:tag`（那时才知道 git sha 与 run 序号）。
        // 用占位符而不是 ${...}，是为了让"这不是一门模板语言"这件事在代码里也看得见。
        String script = "set -e\n"
                + "buildah bud -f " + GitCloneNode.shellQuote(dockerfile)
                + " -t " + IMAGE_REF_PLACEHOLDER + " " + GitCloneNode.shellQuote(context) + "\n"
                + "buildah push --digestfile " + WS + "/.image-digest " + IMAGE_REF_PLACEHOLDER + "\n";

        Step step = new Step();
        step.setName("build-and-push");
        step.setImage("quay.io/buildah/stable:v1.35");
        step.setWorkingDir(WS);
        step.setScript(script);
        return new RenderedStep(List.of(step));
    }
}
