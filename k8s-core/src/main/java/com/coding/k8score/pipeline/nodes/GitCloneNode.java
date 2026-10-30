package com.coding.k8score.pipeline.nodes;

import com.coding.k8score.pipeline.*;
import io.fabric8.kubernetes.api.model.EnvVar;
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
     * 隐式共享 workspace 的挂载点。
     *
     * <p>⚠️ 这个路径**不是 Tekton 的默认值** —— 默认值是 {@code /workspace/<workspace 名>}
     * （旁边还站着 Tekton 自己的 {@code /workspace} 空目录）。是 {@code PipelineRenderer}
     * 在 task 的 workspace 声明里**显式**写了 {@code mountPath}，才让本节点的工作目录成立。
     * 改这里必须同时改那个常量，否则节点写的东西会落到一个没人共享的地方（2026-10-15 真撞到）。
     */
    static final String WS = "/workspace";

    @Override
    public String type() {
        return "git";
    }

    @Override
    public String label() {
        return "git 克隆";
    }

    @Override
    public String description() {
        return "把仓库拉进所有节点共用的工作区";
    }

    @Override
    public String icon() {
        return "FolderOpened";
    }

    @Override
    public String accent() {
        return "success";
    }

    @Override
    public List<String> summaryFields() {
        return List.of("repo", "revision");
    }

    @Override
    public NodeSchema formSchema() {
        // ⚠️ connectionId 的 key 名是**跨模块契约**：platform-api 会照它扫画布、
        // 逐条问「这条连接被授权了吗」（spec §5.3、Review Focus #4）。
        // 改名要同时改 PipelineRunService.CONNECTION_KEYS 与那条护栏测试。
        return new NodeSchema(List.of(
                NodeField.text("connectionId", "git 连接（只列出被授权可用的）", false,
                        "留空 = 匿名拉取，只适用于公开仓库。\n"
                                + "私有仓库要选一条已勾「允许在流水线里使用」的连接（在凭据页勾）。\n"
                                + "凭据只以文件形式挂进构建容器，不会出现在流水线定义与预览里。"),
                NodeField.text("repo", "仓库地址", true,
                        "HTTP(S) 克隆地址。\n例：https://gitlab.example.com/group/repo.git"),
                NodeField.text("revision", "分支 / 标签", false,
                        "留空 = 仓库默认分支。只认分支与标签（浅克隆 --depth 1），"
                                + "填 commit sha 会报 Remote branch not found。\n例：main、v1.2.0"),
                NodeField.text("dir", "目标目录（相对 workspace）", false,
                        "克隆到共享 workspace 的哪个子目录；留空 = 直接放进根目录。\n"
                                + "例：留空 → /workspace 下就是仓库内容；填 src → /workspace/src"
                                + "（下游节点的工作目录与构建上下文记得跟着写 src）"),
                NodeField.advancedText("proxy", "HTTP 代理（高级）",
                        "只在「这个集群出不去」时才填；留空 = 直连。\n"
                                + "例：http://192.168.85.1:7890\n"
                                + "写进 step 的环境变量，所以同一步里别的下载器也一起走它；\n"
                                + "集群内网地址（.svc、内网网段）自动直连，不绕代理。\n"
                                + "⚠️ 不支持带账号密码的代理 —— 那属于凭据，要单独建凭据，别写在这里"),
                BuildStep.memoryField()));
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
        step.setImage(PipelineBaseImages.GIT);
        step.setWorkingDir(WS);
        step.setScript(script.toString());
        // 资源上限：留空 = 一个字都不写（集群默认照旧生效）
        step.setComputeResources(BuildStep.resources(ctx.value("memory")));
        // 代理（留空 = 直连，一个变量都不加）—— GitHub 的 pack 下载实测会在 10MiB 处卡死，
        // 真出不去时这一步就是"能不能拉代码"的分界线
        List<EnvVar> env = ProxyEnv.of(ctx.value("proxy"));
        if (!env.isEmpty()) {
            step.setEnv(env);
        }
        return useCredentials
                ? new RenderedStep(List.of(step),
                        List.of(new RenderedStep.WorkspaceRef(CREDS_WORKSPACE, CREDS_MOUNT)))
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
