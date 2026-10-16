package com.coding.k8score.pipeline.nodes;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.k8score.pipeline.CacheEnv;
import com.coding.k8score.pipeline.NodeContext;
import com.coding.k8score.pipeline.NodeField;
import com.coding.k8score.pipeline.NodeSchema;
import com.coding.k8score.pipeline.PipelineRenderer;
import com.coding.k8score.pipeline.ProxyEnv;
import com.coding.k8score.pipeline.RenderedStep;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.tekton.v1.Step;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 预设构建节点（java / node / go / python）**共用的那一半**：字段清单与渲染的骨架。
 *
 * <p>⚠️ 为什么是静态助手而不是公共基类：四个节点的**差异**（默认镜像、默认命令、
 * 各自多给的环境变量）才是内容，骨架只是抄写 —— 抽基类会把这些差异藏进模板方法里。
 * 这里是"同一段抄四遍不如抄一遍"，不是"它们其实是同一类东西"。
 *
 * <p>四个节点都**默认带依赖缓存**（用户 2026-10-15 定的方案）：环境变量指向 {@code /cache}，
 * 卷的来源由绑定层决定（长期 PVC，拿不到就退化成空目录 —— 见 {@code PipelineRunOperations}）。
 */
final class BuildStep {

    /** 与 {@link GitCloneNode#WS} 同一个路径（那是共享 workspace 的挂载点）。 */
    static final String WS = GitCloneNode.WS;

    /**
     * 写了「内存上限」时，另外三个字段用这一组兜底 —— 抄的是**集群前提文档 §1.2** 里那组数值
     * （`docs/development/cluster-prerequisites.md`）。
     *
     * <p>⚠️ 为什么非得替用户补上这三个：Tekton 的 `default-container-resource-requirements`
     * **只在容器完全没写 resources 时才填**。我们一旦写了 `limits.memory`，Tekton 就一个字都不补，
     * 于是 `requests.cpu` / `limits.cpu` 缺失 → 命名空间配额（含 requests.cpu/limits.cpu 的那种）
     * 直接拒：`must specify limits.cpu for: step-xxx`。四个字段必须**一起**写。
     *
     * <p>⚠️ requests 那档**故意小**：配额的 requests 档通常远小于 limits 档（真集群是 4 CPU/4Gi
     * vs 8 CPU/8Gi）—— 把 requests 跟着用户填的数放大会撞请求配额，那比 OOM 更难查。
     *
     * <p>⚠️ 这三个数与集群那份默认是**各自独立**的：改了那边，这里不会自动跟。
     * 所以它们只是"补全用的兜底"，不是集群配置的唯一真相。
     */
    private static final String REQUEST_CPU = "100m";
    private static final String LIMIT_CPU = "1";

    /**
     * requests.memory 那档，同时也是用户能填的**最小值** —— 上限比它小的话 K8s 会拒
     * （要求 requests ≤ limits），报出来的是另一句看不懂的话。两处用同一个数，别各写一份。
     */
    private static final int MEMORY_FLOOR_MI = 128;
    private static final String REQUEST_MEMORY = MEMORY_FLOOR_MI + "Mi";

    private BuildStep() {
    }

    /**
     * 渲染一个构建步骤：镜像与命令**留空即用默认值**（预设节点的意思就在这里），
     * 环境变量 = 缓存变量 + 这个工具自己的变量 + 代理。
     */
    static RenderedStep render(NodeContext ctx, String defaultImage, String defaultCommand,
                               List<EnvVar> toolEnv) {
        String image = ctx.value("image").isEmpty() ? defaultImage : ctx.value("image");
        String script = ctx.value("command").isEmpty() ? defaultCommand : ctx.value("command");

        Step step = new Step();
        step.setName("build");
        step.setImage(image);
        step.setWorkingDir(workDir(ctx.value("workDir")));
        step.setScript(withDirBanner(script));
        // 留空 = 一个字都不写（集群那套默认资源照旧生效）
        step.setComputeResources(resources(ctx.value("memory")));

        List<EnvVar> env = new ArrayList<>(CacheEnv.of());
        env.addAll(toolEnv);
        env.addAll(ProxyEnv.of(ctx.value("proxy")));
        step.setEnv(env);

        // 缓存 workspace 无条件声明（这个节点存在的意义就是它）；挂的是什么卷由绑定层定
        return new RenderedStep(List.of(step), List.of(new RenderedStep.WorkspaceRef(
                PipelineRenderer.CACHE_WORKSPACE, PipelineRenderer.CACHE_MOUNT)));
    }

    /**
     * 用户填的「内存上限」→ **一整组**容器资源；留空返回 {@code null}（一个字都不写）。
     *
     * <p>⚠️ 字段是**只填数字**的，单位固定 {@code Mi} —— 所以这里只做"是不是数字、够不够大"两件事，
     * 不用再解析 K8s 的 quantity 写法。
     *
     * <p>为什么需要它（用户 2026-10-16 真撞到）：集群侧 Tekton 的默认容器资源是
     * `requests 100m/128Mi + limits cpu 1/memory 1Gi`（见集群前提文档 §1.2），而 Node 会按
     * **容器的 cgroup 上限**推算自己的默认堆大小 —— 1Gi 的容器 ≈ 512MB 堆，
     * 于是 `vue-tsc`（`npm run build` 里与 vite **并行**跑的那个）直接
     * `FATAL ERROR: Reached heap limit`（日志里那行 510.2MB 就是它）。
     * 而只加 `NODE_OPTIONS=--max-old-space-size` 没用：那只是把"撞 Node 自己的堆上限"
     * 换成"被 cgroup 杀掉"（exit 137）。
     *
     * <p>**纯函数**（不碰 IO），所以可单测。
     */
    static ResourceRequirements resources(String memory) {
        String raw = memory == null ? "" : memory.trim();
        if (raw.isEmpty()) {
            return null;
        }
        long mi;
        try {
            mi = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID,
                    "内存上限只能填数字（单位固定 Mi）：" + raw);
        }
        // ⚠️ 上限不能比 requests 小：K8s 要求 requests ≤ limits，否则 Pod 被拒 ——
        //    而报出来的是另一句看不懂的话，所以在这里先拦，并说清是哪个数
        if (mi < MEMORY_FLOOR_MI) {
            throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID,
                    "内存上限至少 " + MEMORY_FLOOR_MI + "Mi（requests 那档就是它，上限不能比它小）：" + raw);
        }
        ResourceRequirements out = new ResourceRequirements();
        // ⚠️ 四个字段一起写，理由见上面那组常量的注释
        out.setRequests(Map.of("cpu", Quantity.parse(REQUEST_CPU),
                "memory", Quantity.parse(REQUEST_MEMORY)));
        out.setLimits(Map.of("cpu", Quantity.parse(LIMIT_CPU),
                "memory", Quantity.parse(mi + "Mi")));
        return out;
    }

    /**
     * 四个节点共用的字段：镜像、命令、工作目录，外加「高级」里的代理与内存上限。
     *
     * @param extraAdvanced 这个工具自己的高级字段（如 Go 的 GOPROXY）—— 一律排在最后，
     *                      属性面板的「高级」开关插在第一个 advanced 字段之前
     */
    static NodeSchema schema(String defaultImage, String defaultCommand, String commandLabel,
                             String commandHelp, List<NodeField> extraAdvanced) {
        List<NodeField> fields = new ArrayList<>();
        fields.add(NodeField.imageOptional("image", "镜像", defaultImage));
        fields.add(NodeField.textarea("command", commandLabel,
                commandHelp + "\n留空 = " + defaultCommand));
        fields.add(NodeField.text("workDir", "工作目录（相对 workspace）", false,
                "留空 = 共享 workspace 的**根目录**，也就是**仓库的根** —— git 克隆节点把仓库内容"
                        + "直接放在那里，**不会**多出一层以仓库名命名的目录。\n"
                        + "例：仓库里只构建前端 → 填 platform-web（不是 my-repo/platform-web）"));
        fields.add(NodeField.advancedText("proxy", "HTTP 代理（高级）",
                "只在「这个集群出不去」时才填；留空 = 直连。\n"
                        + "例：http://192.168.85.1:7890\n"
                        + "下载依赖会走它；集群内网地址（.svc、内网网段）自动直连。"));
        fields.add(NodeField.advancedNumber("memory", "内存上限（高级）", "Mi",
                "只填数字，单位固定 Mi（如 2048）。\n"
                        + "留空 = 用集群给构建容器的那档默认（真集群是 1024Mi —— 前端构建常常不够，"
                        + "npm run build 里的 vue-tsc 会「Reached heap limit」）。\n"
                        + "⚠️ 填了之后 CPU 上限与 requests 由平台补一组小的兜底值（少了它们会被命名空间配额拒）。\n"
                        + "⚠️ 命名空间配额按「所有容器求和」算（一个构建 Pod 约 3 个容器），填大之前先看还剩多少。"));
        fields.addAll(extraAdvanced);
        return new NodeSchema(List.copyOf(fields));
    }

    /**
     * 命令前面那两行「我在哪、这儿有什么」。
     *
     * <p>⚠️ 为什么值得占日志两行（2026-10-16 真跑撞到）：**工作目录填错层级时没有任何提示** ——
     * kubelet 会把不存在的 `workingDir` 直接 {@code mkdir -p} 出来，于是命令在一个**空目录**里跑，
     * 报出来的却是工具自己的错（`npm ci` 说「没有 package-lock.json」、
     * `/tekton/scripts/script-0-xxx: 3: -B: not found`）—— 看起来全是依赖/命令问题，
     * 与「路径多了一层仓库名」这件事毫不相干，查半天查不出来。
     * 把 pwd 与目录内容摊在日志最前面，这类事故第一眼就看得见。
     *
     * <p>只列出前若干项：仓库根可能有上千个文件，日志不该被它淹掉。
     * ⚠️ 有意**不加** {@code set -e} —— 那会悄悄改掉用户命令的语义（用户可能写着
     * {@code a || true}、或依赖不在第一行失败时继续），不是这个改动该顺手做的事。
     */
    static String withDirBanner(String script) {
        return "echo \"工作目录：$(pwd)\"\n"
                + "ls -A | head -20\n"
                + script;
    }

    /**
     * 把用户填的工作目录解析成绝对路径。
     *
     * <p>空 / {@code .} / {@code ./} 都归到 workspace 根 —— 不然会渲染出 {@code /workspace/.}
     * 这种路径：Tekton 收得下，但日志里看一眼就让人怀疑是不是配错了。
     */
    static String workDir(String raw) {
        String dir = raw == null ? "" : raw.trim();
        while (dir.startsWith("./")) {
            dir = dir.substring(2);
        }
        while (dir.endsWith("/")) {
            dir = dir.substring(0, dir.length() - 1);
        }
        return dir.isEmpty() || ".".equals(dir) ? WS : WS + "/" + dir;
    }

    static EnvVar env(String name, String value) {
        return new EnvVar(name, value, null);
    }
}
