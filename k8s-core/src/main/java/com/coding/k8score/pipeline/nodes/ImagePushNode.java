package com.coding.k8score.pipeline.nodes;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.pipeline.PipelineConnectionRef;
import com.coding.k8score.pipeline.*;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.tekton.v1.Step;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 构建并推送镜像。**真特殊**的节点之二：Buildah（per-Pod、不用共享构建服务）、
 * 推送凭据由平台注入、产出 digest。
 *
 * <p>⚠️ tag 由**四选一下拉**决定（git 短 sha / 运行序号 / 时间戳 / 自定义），
 * 不发明 {@code ${...}} 模板语法（spec §2.2）。
 * 真正的拼接在组装 {@code PipelineRun} 时做 —— 那里才知道 git sha 与 run 序号。
 */
@Component
public class ImagePushNode implements PipelineNode {

    /** 见 {@link GitCloneNode#WS} —— 与 Tekton 的默认 workspace 挂载点对齐。 */
    static final String WS = "/workspace";

    /**
     * 存储驱动：**不用 overlay**。见 {@link #render} 里那段注释 —— 容器里没有 /dev/fuse，
     * 也没有 CAP_SYS_ADMIN，overlay 的两条路（fuse-overlayfs / 内核 overlay）都走不通。
     * vfs 是唯一"压根不挂载"的档位：整棵树按目录拷贝，因此慢、吃盘。
     */
    private static final String STORAGE_DRIVER = "vfs";

    /**
     * 隔离级别：**不用 runc**。默认的 oci 隔离要起 runc，而 runc 建命名空间（CLONE_NEWNS）
     * 要 CAP_SYS_ADMIN —— 普通容器同样没有。chroot 只做一次 chroot(2)，
     * 而 CAP_SYS_CHROOT **在容器的默认能力集里**（默认集一共 14 个，它在）。
     */
    private static final String ISOLATION = "chroot";

    /**
     * 短名解析的候选源。**只留 docker.io** —— 也就是"Dockerfile 里的短名按 Docker 的规矩来"
     * （`foo` → `docker.io/library/foo`，`me/foo` → `docker.io/me/foo`）。
     * 详见 {@link #shortNamePrologue()}。
     */
    private static final String REGISTRIES_CONF = "/etc/containers/registries.conf";
    private static final String REGISTRIES_CONF_D = "/etc/containers/registries.conf.d";
    private static final String SEARCH_REGISTRIES_LINE = "unqualified-search-registries = [\"docker.io\"]";

    /**
     * 时间戳 tag 的形状：{@code yyyyMMddHHmmss}。
     *
     * <p>与 platform-api 生成 run 名用的是**同一个格式**（{@code PipelineRunService.STAMP}）——
     * 两个数对着看的时候能一眼对上是哪次运行。
     */
    private static final DateTimeFormatter TAG_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

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
                                new NodeField.Option("time", "时间戳（yyyyMMddHHmmss）"),
                                new NodeField.Option("fixed", "自定义固定值")), "git-sha",
                        "选哪个，看你要的是「同一份代码永远同一个 tag」还是「每次一个新的」：\n"
                                + "  · git 短 sha —— 同一份代码永远是同一个 tag，重跑不会覆盖旧镜像；\n"
                                + "    仓库没给出 sha 时退成 manual\n"
                                + "  · 本次运行序号 —— 每次运行都不一样（就是这次运行的运行名）\n"
                                + "  · 时间戳 —— 也是每次一个新的，但一眼看得出是什么时候打的\n"
                                + "  · 自定义固定值 —— 手写一个，覆盖由你自己负责\n"
                                + "⚠️ 时间戳用的是平台服务器那一侧的时钟与时区，不是你本机的。\n"
                                + "⚠️ 时间戳在「渲染这一趟」里只取一次：同一趟里的两个镜像节点拿到的是同一个 tag\n"
                                + "   （同一次运行的产物配套），换个时间重跑就是新的。"),
                NodeField.text("fixedTag", "固定 tag（仅「自定义固定值」时用）", false,
                        "只有上面的 tag 来源选「自定义固定值」时才用。\n例：v1.0.0、latest"),
                NodeField.text("dockerfile", "Dockerfile 路径", false,
                        "留空 = 用 workspace 根目录下的 Dockerfile。\n"
                                + "相对「共享 workspace 的根」，可以指向构建上下文之外。\n"
                                + "例：在仓库根 → 留空；在前端目录 → frontend/Dockerfile；"
                                + "统一放一处 → docker/Dockerfile（此时上下文一般留空）"),
                NodeField.text("context", "构建上下文", false,
                        "构建工具能看到的那一个目录 —— Dockerfile 里的 COPY/ADD 的路径都是相对它的，"
                                + "不是相对仓库根（与 Dockerfile 本身放在哪儿无关）。\n"
                                + "留空 = 共享 workspace 的根（git 克隆节点把代码放进来的地方）。\n"
                                + "⚠️ 先看你的 Dockerfile 是照哪个视角写的，再决定这里填不填：\n"
                                + "  · 写着 COPY platform-web/package.json（带目录前缀）→ 仓库根视角，"
                                + "这里必须留空；\n"
                                + "    填了 platform-web 它就会去找 platform-web/platform-web/package.json，"
                                + "报 no such file or directory\n"
                                + "  · 写着 COPY package.json ./ → 工程目录视角，"
                                + "这里填那个目录（如 platform-web），Dockerfile 路径同时写全："
                                + "platform-web/Dockerfile\n"
                                + "例：\n"
                                + "  · 仓库根就是构建目录 → 留空\n"
                                + "  · 一个仓库要出多个镜像（各目录一份 Dockerfile）→ 分别填各自的目录\n"
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
        String tagPrologue = tagPrologue(ctx.value("tagSource"), ctx.value("fixedTag"),
                TAG_STAMP.format(ctx.renderedAt()));

        // ⚠️ 三处 Tekton 的坑都在这段里：
        //   1. ⭐ 2026-10-16 真跑撞到：**别用 `$$( )` 那个"转义 shell 命令替换"的写法**。
        //      Tekton 在这一版里**没有**把 `$$` 还原成 `$`，于是 shell 把 `$$` 当**进程号**展开：
        //        IMAGE='docker.io/.../platform-web':"$$(cat '/workspace/.git-sha' … )"
        //        → docker.io/.../platform-web:15(cat '/workspace/.git-sha' … )
        //          （15 就是那个 shell 的 PID）→ buildah: invalid reference format
        //      现在**完全不用命令替换**：`read` 进一个普通变量 TAG（`$TAG` / `$IMAGE` 这种
        //      普通变量 Tekton 从来不碰，脚本里一直这么用）。
        //   2. `$(context.pipelineRun.name)` 是 Tekton **自己的**内建变量，**要它替换**，所以不转义。
        //   3. 字面量（固定 tag）别包 `$( )`：Tekton 替换不了就留给 shell，而 shell 会把它
        //      当命令替换去执行 —— `$('v1')` 会去跑命令 `v1`，tag 变空（2026-10-15 抓到的）。
        String script = "set -e\n"
                + contextBanner(context, dockerfile)
                + shortNamePrologue()
                + tagPrologue
                + "IMAGE=" + image + ":\"$TAG\"\n"
                + "echo \"目标镜像：$IMAGE\"\n"
                + "buildah bud -f " + GitCloneNode.shellQuote(dockerfile) + " -t \"$IMAGE\" " + GitCloneNode.shellQuote(context) + "\n"
                + "buildah push --digestfile " + GitCloneNode.shellQuote(GitCloneNode.WS + "/.image-digest") + " \"$IMAGE\"\n";

        Step step = new Step();
        step.setName("build-and-push");
        step.setImage(PipelineBaseImages.BUILDAH);
        step.setWorkingDir(WS);
        step.setScript(script);
        // 推送凭据以文件形式挂进来（k8s-server 按 platform-api 给的连接建 Secret）——
        // buildah 认 REGISTRY_AUTH_FILE 这个环境变量
        // ⚠️ 代理是**加进这个列表**，不是替换它：冲掉 REGISTRY_AUTH_FILE = 推送 401
        List<EnvVar> env = new ArrayList<>();
        env.add(new EnvVar("REGISTRY_AUTH_FILE", GitCloneNode.CREDS_MOUNT + "/config.json", null));
        // ⚠️⚠️ 2026-10-16 真跑撞到（"fuse: device not found, try 'modprobe fuse' first"）：
        //    buildah 默认用 overlay 存储驱动，而**容器里根本没有 /dev/fuse** —— k8s 给每个容器的
        //    是最小 /dev，只有 null/zero/full/tty/random/urandom（在真集群里 ls 过）。
        //    实况日志：
        //      using mount program /usr/bin/fuse-overlayfs: unknown argument ignored: lazytime
        //      fuse: device not found, try 'modprobe fuse' first
        //      fuse-overlayfs: cannot mount: No such file or directory
        //      Error: mounting new container: … exit status 1
        //    换成内核 overlay 也一样不行：容器默认能力集里没有 CAP_SYS_ADMIN，mount(2) 会 EPERM。
        //    buildah 镜像是**照着无根（rootless）用法做**的，默认就挂 fuse-overlayfs，
        //    所以哪怕这里是 root 也照样撞上。
        //
        //    这两行是"压根不挂载"的那条路，**集群侧一个字都不用改**：
        //      · STORAGE_DRIVER=vfs —— vfs 驱动的 Mount 是个空操作（就是目录本身），不需要 fuse，
        //        也不需要 mount(2)。代价：每一层都是整棵树的拷贝，慢且吃盘
        //        （构建 Pod 的临时盘，节点上还有 40G+，够；但别指望它像 overlay 那样秒开）。
        //      · BUILDAH_ISOLATION=chroot —— 不启 runc，就不建命名空间、不要 CAP_SYS_ADMIN。
        //
        //    为什么不上"加 privileged + hostPath 挂 /dev/fuse"那条路：那要同时改
        //    `set-security-context`（本集群是 false，Tekton 会把 step 的 securityContext
        //    整个丢掉）、给到 privileged、还得给 Pod 塞 podTemplate —— 为一个构建节点
        //    把集群的隔离打开，不划算。
        env.add(new EnvVar("STORAGE_DRIVER", STORAGE_DRIVER, null));
        env.add(new EnvVar("BUILDAH_ISOLATION", ISOLATION, null));
        env.addAll(ProxyEnv.of(ctx.value("proxy")));
        step.setEnv(env);
        return new RenderedStep(List.of(step), List.of(new RenderedStep.WorkspaceRef(
                GitCloneNode.CREDS_WORKSPACE, GitCloneNode.CREDS_MOUNT)));
    }

    /**
     * 开跑前把"上下文是谁、**里面到底有什么**、Dockerfile 是哪份"摊在日志最前面。
     *
     * <p>⚠️ 2026-10-16 真跑撞到：
     * <pre>
     * Error: building at STEP "COPY platform-web/package.json platform-web/package-lock.json ./":
     *   checking on sources under "/workspace/platform-web": copier: stat:
     *   "/platform-web/package.json": no such file or directory
     * </pre>
     * 病因是**配置与 Dockerfile 对不上**：Dockerfile 是**仓库根视角**写的
     * （{@code COPY platform-web/xxx …}），而节点的「构建上下文」填了 {@code platform-web}
     * → buildah 去找 {@code /workspace/platform-web/platform-web/xxx}。
     * 可 buildah 那句话要琢磨半天才反应过来，所以这里（跟预设构建节点那两行同一个理由）
     * 把三样都摊出来 —— **上下文目录里有没有 {@code platform-web} 那个子目录，一眼就知道**。
     *
     * <p>两个路径用的是**同一套归一化规则**（相对共享 workspace 的根），
     * 所以直接借 {@link BuildStep#workDir}：留空 = workspace 根、{@code ./} 与前后的 {@code /} 都抹掉。
     */
    private static String contextBanner(String context, String dockerfile) {
        String ctxDir = BuildStep.workDir(context);
        String dfPath = BuildStep.workDir(dockerfile);
        return "echo \"构建上下文：" + ctxDir + "（COPY/ADD 的路径都相对它）\"\n"
                + "ls -A " + GitCloneNode.shellQuote(ctxDir) + " | head -20\n"
                + "echo \"Dockerfile：" + dfPath + "\"\n";
    }

    /**
     * 把"短名"的候选源钉死成 {@code docker.io}，让 Dockerfile 里的 {@code FROM} 按 **Docker 的规矩**
     * 解析（{@code foo} → {@code docker.io/library/foo}，{@code me/foo} → {@code docker.io/me/foo}）。
     *
     * <p>⚠️ 2026-10-16 真跑撞到（{@code FROM maven:3.9-eclipse-temurin-21}）：
     * <pre>
     * Error: creating build container: short-name resolution enforced but cannot prompt without a TTY
     * </pre>
     * 同一个 Dockerfile 里的 {@code FROM node:20-alpine} 却没事 —— 差别在日志里：
     * <pre>
     * Resolved "node" as an alias (/etc/containers/registries.conf.d/000-shortnames.conf)
     * </pre>
     * 短名解析是**两段**：先查 alias 表（命中且只有一个候选就直接用），查不到才退到
     * {@code unqualified-search-registries}。buildah 镜像（Fedora 底）那张表里有**不止一个**
     * 候选源（fedora / redhat / docker.io 之类），于是它想去**交互式问人选哪个** ——
     * 构建里没有 TTY，直接报错。{@code maven} 不在 alias 表里，撞的是这条；{@code node} 在。
     *
     * <p>两个路径**都写**（主文件 + 编号最大的 drop-in）：drop-in 是**按文件名顺序合并**的，
     * 但"后面覆盖前面"还是"第一个非空生效"各版本说法不一 —— 两条都铺上，
     * 不管合并顺序如何，生效的都是我们这一份，同时**不删**镜像自带的那张 alias 表。
     */
    private static String shortNamePrologue() {
        String write = "printf '%s\\n' '" + SEARCH_REGISTRIES_LINE + "' > ";
        return "mkdir -p " + REGISTRIES_CONF_D + "\n"
                + write + REGISTRIES_CONF + "\n"
                + write + REGISTRIES_CONF_D + "/999-platform-dockerhub.conf\n";
    }

    /**
     * tag 的三种来源 —— 都收敛成一个**普通 shell 变量** {@code TAG} 的赋值语句（含换行），
     * 之后统一拼进 {@code IMAGE=...:"$TAG"}。
     *
     * <p>为什么是"普通变量 + 赋值"而不是"一段表达式直接拼在冒号后"（2026-10-16 改）：
     * 三种来源里只有 {@code run-id} 需要 **Tekton** 替换（它是 Tekton 的内建变量），
     * 另外两种是 shell 的事 —— 而 shell 的**命令替换** `$( )` 恰好和 Tekton 的变量语法撞车。
     * 之前的写法是用 `$$( )` 去转义，但这一版 Tekton **没有**把 `$$` 还原成 `$`，
     * shell 就当进程号展开了（真跑：tag 变成 `15(cat '/workspace/.git-sha' …)`）。
     * 现在 git-sha 用 {@code read} 读文件，**一个命令替换都不需要**；
     * 普通变量（`$TAG` / `$IMAGE`）Tekton 从来不碰 —— 不跟它的转义规则打赌。
     */
    private static String tagPrologue(String tagSource, String fixedTag, String renderedAt) {
        String shaFile = GitCloneNode.shellQuote(GitCloneNode.GIT_SHA_FILE);
        return switch (tagSource) {
            // 字面量：别包 $( )，否则 shell 会把它当命令替换去执行（见上面第 3 条）
            case "fixed" -> "TAG=" + GitCloneNode.shellQuote(fixedTag.isEmpty() ? "latest" : fixedTag) + "\n";
            // Tekton 内建变量：**要它替换**，所以不转义（替换发生在 controller 建 TaskRun 时）
            case "run-id" -> "TAG=\"$(context.pipelineRun.name)\"\n";
            // 渲染时刻（yyyyMMddHHmmss）：**平台在渲染时就算好**，脚本里只是个字面量。
            // ⚠️ 刻意不在脚本里写 `$(date +%Y%m%d%H%M%S)` —— 那又变成跟 Tekton 的 `$()` 规则打赌，
            //    而 `$$` 那一次已经证明这一版不会替我们保留 shell 的命令替换。
            //    顺带两个好处：一条流水线里两个镜像节点拿到的是**同一个** tag；
            //    渲染出的 YAML 里直接看得见用的是哪个 tag。
            case "time" -> "TAG=" + GitCloneNode.shellQuote(renderedAt) + "\n";
            // git 短 sha：克隆节点落的文件。⚠️ `read` 失败（文件没有结尾换行）时 `||` 兜一下 ——
            // 否则 `set -e` 会把整步干掉
            default -> "TAG=manual\n"
                    + "if [ -s " + shaFile + " ]; then\n"
                    + "  read -r TAG < " + shaFile + " || TAG=manual\n"
                    + "fi\n";
        };
    }
}
