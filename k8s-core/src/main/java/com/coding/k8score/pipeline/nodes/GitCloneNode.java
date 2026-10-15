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
        // ⚠️ connectionId 的 key 名是**跨模块契约**：platform-api 会照它扫画布、
        // 逐条问「这条连接被授权了吗」（spec §5.3、Review Focus #4）。
        // 改名要同时改 PipelineRunService.CONNECTION_KEYS 与那条护栏测试。
        return new NodeSchema(List.of(
                NodeField.text("connectionId", "git 连接（只列出被授权可用的）", false),
                NodeField.text("repo", "仓库地址", true),
                NodeField.text("revision", "分支 / commit", false),
                NodeField.text("dir", "目标目录（相对 workspace）", false)));
    }

    @Override
    public RenderedStep render(NodeContext ctx) {
        String repo = ctx.value("repo");
        String revision = ctx.value("revision");
        String dir = ctx.value("dir").isEmpty() ? "." : ctx.value("dir");
        boolean useCredentials = ctx.connection(ctx.value("connectionId")) != null;

        StringBuilder script = new StringBuilder("set -e\n");
        if (useCredentials) {
            // ⚠️ 凭据**只以文件形式挂进来**（platform-api 解密后交给 k8s-server 建 Secret），
            // 绝不写进脚本：那会让凭据进 PipelineRun 的 spec，而预览页把渲染结果原样给用户看。
            script.append("cp ").append(CREDS_MOUNT).append("/git-credentials \"$HOME/.netrc\"\n");
            script.append("chmod 600 \"$HOME/.netrc\"\n");
        }
        script.append("git clone --depth 1 ");
        if (!revision.isEmpty()) {
            script.append("--branch ").append(shellQuote(revision)).append(' ');
        }
        script.append(shellQuote(repo)).append(' ').append(shellQuote(dir)).append('\n');
        // 把短 sha 落下来给下游拼 tag 用。
        // ⚠️ shell 的命令替换必须写成 $$(...) —— Tekton 会把未转义的 $(x) 当成**自己的**变量替换，
        // 解析不到就直接报错（这是 Tekton 脚本最常见的坑）。
        script.append("git -C ").append(shellQuote(dir))
              .append(" rev-parse --short HEAD > ").append(shellQuote(GIT_SHA_FILE)).append('\n');

        Step step = new Step();
        step.setName("clone");
        step.setImage("alpine/git:2.45.2");
        step.setWorkingDir(WS);
        step.setScript(script.toString());
        return useCredentials
                ? new RenderedStep(List.of(step), List.of(CREDS_WORKSPACE))
                : new RenderedStep(List.of(step));
    }

    /** 挂凭据的那个 workspace 的名字与挂载点 —— 与 {@code PipelineRenderer} 里的声明成对。 */
    static final String CREDS_WORKSPACE = "creds";
    static final String CREDS_MOUNT = "/creds";

    /** 克隆出来的短 sha 落在这里，给下游（打镜像节点）拼 tag 用。 */
    static final String GIT_SHA_FILE = WS + "/.git-sha";

    /** 只加单引号并把内部的单引号转义 —— 不引入完整 shell 解析，够用且可预测。 */
    static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
