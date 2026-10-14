package com.coding.k8score.pipeline.nodes;

import com.coding.k8score.pipeline.*;
import io.fabric8.tekton.v1.Step;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * git 克隆。**真特殊**的节点之一：接 S0.2 的 git 连接（凭据由平台渲染时注入成 workspace 或 secret）、
 * 把源码写进**共享 workspace**，下游节点不用做任何事就能看到。
 *
 * <p>⚠️ 凭据注入的落点在组装 {@code PipelineRun} 的那一层（推的是 docker config 与 git 凭据），
 * 本节点只负责「怎么克隆」这件事。
 */
@Component
public class GitCloneNode implements PipelineNode {

    /**
     * 隐式共享 workspace 的挂载点。**它与 Tekton 的默认值对齐** ——
     * 渲染器只给 {@code WorkspaceDeclaration} 设了 name、没设 mountPath，Tekton 就挂到这里。
     * 改这个名字要同时改渲染器里的 mountPath，否则节点的工作目录会指向一个不存在的路径。
     */
    static final String WS = "/workspace";

    @Override
    public String type() {
        return "git";
    }

    @Override
    public NodeSchema formSchema() {
        return new NodeSchema(List.of(
                NodeField.text("repo", "仓库地址", true),
                NodeField.text("revision", "分支 / commit", false),
                NodeField.text("dir", "目标目录（相对 workspace）", false)));
    }

    @Override
    public RenderedStep render(NodeContext ctx) {
        String repo = ctx.value("repo");
        String revision = ctx.value("revision");
        String dir = ctx.value("dir").isEmpty() ? "." : ctx.value("dir");

        StringBuilder script = new StringBuilder("set -e\n");
        script.append("git clone --depth 1 ");
        if (!revision.isEmpty()) {
            script.append("--branch ").append(shellQuote(revision)).append(' ');
        }
        script.append(shellQuote(repo)).append(' ').append(shellQuote(dir)).append('\n');

        Step step = new Step();
        step.setName("clone");
        step.setImage("alpine/git:2.45.2");
        step.setWorkingDir(WS);
        step.setScript(script.toString());
        return new RenderedStep(List.of(step));
    }

    /** 只加单引号并把内部的单引号转义 —— 不引入完整 shell 解析，够用且可预测。 */
    static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
