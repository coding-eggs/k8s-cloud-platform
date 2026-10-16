package com.coding.k8score.pipeline;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.pipeline.PipelineConnectionRef;
import io.fabric8.tekton.v1.EmbeddedTask;
import io.fabric8.tekton.v1.PipelineSpec;
import io.fabric8.tekton.v1.PipelineTask;
import io.fabric8.tekton.v1.PipelineWorkspaceDeclaration;
import io.fabric8.tekton.v1.WorkspaceDeclaration;
import io.fabric8.tekton.v1.WorkspacePipelineTaskBinding;
import io.fabric8.tekton.v1.WhenExpression;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 画布 → Tekton {@code PipelineSpec}。**纯函数、无 IO、可单测**。
 *
 * <p>三条硬规则（spec §3.1）：
 * <ol>
 *   <li><b>有向无环</b> —— 有环直接拒，绝不让它走到集群；</li>
 *   <li><b>连线 → {@code runAfter}</b>；</li>
 *   <li><b>没有连线 = 并发</b>（Tekton 的默认语义）—— <b>不是错误</b>，UI 要给提示。</li>
 * </ol>
 *
 * <p>⚠️ <b>每条 task 的 {@code taskSpec} 全部内联</b>（台账 S3.5.3 已定）——
 * 消掉跨命名空间引用与 ClusterTask 废弃那一堆问题。
 *
 * <p>它是实例方法（要拿 {@link PipelineNodeRegistry}），但**仍然无 IO、无状态**：
 * 同一份画布渲染两次结果字节等价。`@Component` 只是让它能被 {@link PipelineCanvasOperations} 注入。
 */
@Component
public final class PipelineRenderer {

    /** 挂凭据的 workspace：名字与挂载点与 {@code GitCloneNode} / {@code ImagePushNode} 里的常量成对。 */
    public static final String CREDS_WORKSPACE = "creds";
    public static final String CREDS_MOUNT = "/creds";

    /**
     * 隐式共享 workspace 的挂载点。**必须显式声明**（见 {@link #declaration} 的调用点）。
     *
     * <p>⚠️ Tekton 对**没写 {@code mountPath}** 的 workspace 默认挂到 {@code /workspace/<workspace 名>}
     * （{@code pkg/pod/pod.go} 里 {@code filepath.Join(workspaceDir, ws.Name)}），而 {@code /workspace}
     * 本身站的是 Tekton 自己的空目录（{@code tekton-internal-workspace}）。真撞到过（2026-10-15）：
     * git 节点写的东西全落进那个空目录、**根本没进 PVC** —— 名为"共享 workspace"，
     * 实际下游一个字节都看不到，且 {@code git clone} 会因为目录非空直接失败。
     */
    public static final String WORKSPACE_MOUNT = "/workspace";

    /**
     * 依赖缓存的 workspace：名字与挂载点与节点的缓存环境变量成对（见 {@code CacheEnv}）。
     *
     * <p>⚠️ 它与 {@code creds} 的区别是**卷的来源**：creds 绑的是每次运行现建的 Secret，
     * 而 cache 绑的是一块**跨运行保留**的 PVC（平台按 集群+命名空间 建一块，跑完不删）——
     * 所以「每次重下 jar」才有解（2026-10-15 定的方案）。
     */
    public static final String CACHE_WORKSPACE = "cache";
    public static final String CACHE_MOUNT = "/cache";

    /**
     * 「失败也继续」的取值 —— Tekton 的 {@code task.onError} 目前**只有这一个合法值**，
     * 所以画布上它是个开关，落到 YAML 里是这个字符串（原样透传，不做映射表）。
     */
    public static final String CONTINUE = "continue";

    /**
     * 收尾节点的执行时机（画布上的三选一）。
     *
     * <p>⚠️ 这三个字符串**前端也写着一份**（{@code NodePropsPanel.vue} 的「运行控制」段）——
     * 改这里要同时改那边。
     */
    public static final String WHEN_ALWAYS = "always";
    public static final String WHEN_FAILED = "failed";
    public static final String WHEN_SUCCEEDED = "succeeded";

    /**
     * 聚合状态变量。**只能用在 {@code finally} 里** —— 写在 {@code tasks[]} 的 {@code when} 上，
     * 校验 webhook 会直接拒（见下面 {@link #finallyWhenOf} 的说明）。
     */
    public static final String AGGREGATE_STATUS = "$(tasks.status)";

    private final PipelineNodeRegistry nodes;

    public PipelineRenderer(PipelineNodeRegistry nodes) {
        this.nodes = nodes;
    }

    /**
     * @param workspaceClaimName 隐式共享 workspace 的名字。平台注入到**所有**节点，用户不感知
     */
    public PipelineSpec render(PipelineCanvas canvas, String workspaceClaimName) {
        return render(canvas, workspaceClaimName, Map.of());
    }

    /**
     * @param connections 本次运行可用的连接（已授权、已解密），按 id 索引。
     *                    空 map = 预览/校验路径（**不该拿到凭据**）。
     */
    public PipelineSpec render(PipelineCanvas canvas, String workspaceClaimName,
                               Map<String, PipelineConnectionRef> connections) {
        if (canvas == null || canvas.nodes() == null || canvas.nodes().isEmpty()) {
            throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID, "流水线至少要有一个节点");
        }
        Map<String, PipelineCanvas.CanvasNode> byId = new LinkedHashMap<>();
        for (PipelineCanvas.CanvasNode n : canvas.nodes()) {
            if (byId.put(n.id(), n) != null) {
                throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID, "节点 id 重复：" + n.id());
            }
        }
        List<PipelineCanvas.CanvasEdge> edges = canvas.edges() == null ? List.of() : canvas.edges();

        // 连线按 to 归组 —— 一个节点可以被多个上游指向（汇合）
        Map<String, Set<String>> runAfter = new LinkedHashMap<>();
        for (PipelineCanvas.CanvasEdge e : edges) {
            PipelineCanvas.CanvasNode from = byId.get(e.from());
            if (from == null) {
                throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID,
                        "连线指向了不存在的节点：" + e.from());
            }
            PipelineCanvas.CanvasNode to = byId.get(e.to());
            if (to == null) {
                throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID,
                        "连线指向了不存在的节点：" + e.to());
            }
            // ⚠️ 收尾节点**不参与顺序**：Tekton 的 finally 既不能写 runAfter、也不按顺序跑
            // （它们同时开跑，见 spec 里那条「已知限制」）。画布上还留着一根线就是画布在说谎 ——
            // 拒掉，而不是把那根线静默丢掉（用户会以为它生效了）。
            if (from.isFinally() || to.isFinally()) {
                throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID,
                        "收尾执行的节点不参与顺序，不能连线：" + (from.isFinally() ? e.from() : e.to()));
            }
            runAfter.computeIfAbsent(e.to(), k -> new LinkedHashSet<>()).add(e.from());
        }
        rejectCycles(byId.keySet(), edges);

        long finallyCount = canvas.nodes().stream().filter(PipelineCanvas.CanvasNode::isFinally).count();
        if (finallyCount == canvas.nodes().size()) {
            // 全是收尾节点 = Tekton 那边 tasks 为空，pipeline 本身就不成立 —— 报错要报在我们自己嘴里
            throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID,
                    "全是收尾执行的节点 —— 至少要有一个不在收尾里跑的节点");
        }

        List<PipelineTask> tasks = new ArrayList<>();
        // 收尾节点单独一列（Tekton 的 finally[]）。**只在真有收尾节点时才写这个键** ——
        // 普通流水线的 YAML 里不该冒出一个空的 `finally:`
        List<PipelineTask> finallyTasks = new ArrayList<>();
        // 谁要额外的 workspace（凭据 / 缓存）就把它记下来 —— 声明一次，
        // **只在实际用到时才声明**（不用就不建那个 Secret、不挂那块缓存卷）
        Map<String, String> extraWorkspaces = new LinkedHashMap<>();
        // ⭐ 渲染时刻**整张画布只取一次**：一张流水线里的两个镜像节点要拿到**同一个** tag
        //    （同一次运行的产物配套），各自 `now()` 会跨秒、跨出两个不一样的 tag 来。
        LocalDateTime renderedAt = LocalDateTime.now();
        for (PipelineCanvas.CanvasNode n : canvas.nodes()) {
            PipelineNode impl = nodes.of(n.type());
            NodeContext ctx = new NodeContext(n.id(), n.id(), workspaceClaimName, n, connections, renderedAt);
            RenderedStep rendered = impl.render(ctx);
            // 挂载点**由节点给**（凭据 /creds、缓存 /cache）：以前这里对所有 extra 一律套 CREDS_MOUNT，
            // 加第二个额外 workspace 时就会静默挂错地方
            rendered.workspaces().forEach(w -> extraWorkspaces.putIfAbsent(w.name(), w.mountPath()));

            // ⚠️ 内联 TaskSpec 的字段是**平铺**在 taskSpec 上的，**不是**装在它的 `spec` 里。
            // Tekton v1 的 EmbeddedTask 把 TaskSpec 内联在自己身上
            // （pipeline_types.go：`TaskSpec `json:",inline,omitempty"``），它自己那个 `spec`
            // 字段是给**自定义任务**留的 RawExtension。塞进 `spec` 的后果是内联的那一份为空 ——
            // 集群的 admission webhook 会拒：missing field(s): ...taskSpec.steps（2026-10-15 线上回归）。
            EmbeddedTask taskSpec = new EmbeddedTask();
            taskSpec.setSteps(rendered.steps());
            // 隐式共享 workspace：每个 task 都挂上，用户不用管。
            // ⚠️ 挂载点**显式写死**成 /workspace（Tekton 的默认值是 /workspace/<名字>，见 WORKSPACE_MOUNT）
            List<WorkspaceDeclaration> ws = new ArrayList<>();
            ws.add(declaration(workspaceClaimName, WORKSPACE_MOUNT));
            for (RenderedStep.WorkspaceRef extra : rendered.workspaces()) {
                ws.add(declaration(extra.name(), extra.mountPath()));
            }
            taskSpec.setWorkspaces(ws);

            PipelineTask task = new PipelineTask();
            task.setName(n.id());                      // 节点 id 就是 task 名
            // 用户起的名字**只给人看**：写成 Tekton 的 displayName（kubectl 里也认得出来），
            // 绝不参与 task 名 —— 日志、运行状态、历史键（stepsJson）全按 id 走（见 CanvasNode 的 javadoc）
            String displayName = displayNameOf(n);
            if (displayName != null) {
                task.setDisplayName(displayName);
            }
            task.setTaskSpec(taskSpec);
            // ⚠️ 上面是**声明**（这个 task 需要哪些 workspace），这里是**绑定**（它们各接到
            // pipeline 的哪一个上）—— 两件事，缺一不可。
            // Tekton 只会把**脚本里用 $(workspaces.X.path) 引用过**的 workspace 自动传播给
            // TaskRun（pkg/workspace/apply.go 的 FindWorkspacesUsedByTask 是扫替换变量的），
            // 而我们的节点脚本写的是字面量 /workspace、/creds —— 一个都不会被传播。
            // 不显式绑，TaskRun 就拿到 0 个绑定，校验直接拒：
            //   declared workspace "ws" is required but has not been bound
            // 一秒内失败、Pod 都没起来（所以连日志都没有）——2026-10-15 第二次真跑撞到。
            // 只写 name：Tekton 会把它接到 pipeline 里同名的那个 workspace 上。
            task.setWorkspaces(ws.stream().map(PipelineRenderer::taskWorkspaceBinding).toList());

            // 失败也继续（Tekton 的 `onError: continue`）：这一节点挂了不阻断它的下游 ——
            // 「失败分支」在这套画布上只有这一种表达（原因见 finallyWhenOf 上那段说明）。
            if (n.onError() != null && !n.onError().isBlank()) {
                if (!CONTINUE.equals(n.onError())) {
                    throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID,
                            "节点 " + n.id() + " 的失败处理认不出来：" + n.onError()
                                    + "（只能是 " + CONTINUE + "）");
                }
                task.setOnError(CONTINUE);
            }

            Set<String> after = runAfter.get(n.id());
            if (after != null && !after.isEmpty()) {
                task.setRunAfter(new ArrayList<>(after));
            }
            if (n.isFinally()) {
                // 收尾节点没有 runAfter（上面已经拒掉了连到它的线）—— 它跑不跑由聚合状态决定
                WhenExpression when = finallyWhenOf(n);
                if (when != null) {
                    task.setWhen(List.of(when));
                }
                finallyTasks.add(task);
            } else {
                tasks.add(task);
            }
        }

        List<PipelineWorkspaceDeclaration> declared = new ArrayList<>();
        declared.add(pipelineWorkspace(workspaceClaimName));
        for (String extra : extraWorkspaces.keySet()) {
            declared.add(pipelineWorkspace(extra));
        }
        PipelineSpec spec = new PipelineSpec();
        spec.setTasks(tasks);
        if (!finallyTasks.isEmpty()) {
            spec.setFinally(finallyTasks);
        }
        spec.setWorkspaces(declared);
        return spec;
    }

    /**
     * 收尾节点的执行时机 → Tekton 的 {@code when}（{@code null} = 总是执行，不写 {@code when}）。
     *
     * <p>⚠️ <b>为什么条件只能写在这里</b>（2026-10-16 用 {@code --dry-run=server} 实测）：
     * {@code tasks[]} 里的 {@code when} **一律不能引用任务的执行状态** —— 单个的
     * {@code $(tasks.X.status)} 与聚合的 {@code $(tasks.status)} 都拒，原话是
     * <i>pipeline tasks can not refer to execution status of any other pipeline task or
     * aggregate status of tasks</i>。执行状态条件**只有 finally 能用**。
     * 于是「失败/成功如何走」的落点就是：普通节点上的 {@code onError} + 收尾节点上的这个 {@code when}。
     *
     * <p>⚠️ {@link #WHEN_FAILED} 有意**不**写 {@code values: ["Failed"]}：
     * 聚合值的档位是 {@code Succeeded / Completed / Failed / None}，而 {@code Completed}
     * 那一档是「有任务被跳过」—— 平台上唯一能造成跳过的原因就是上游失败（{@code tasks[]} 里的
     * {@code when} 我们根本不渲染），所以「不是全成功、也不是全跳过」才是在任何档位口径下
     * 都成立的「前面失败了」。
     */
    private static WhenExpression finallyWhenOf(PipelineCanvas.CanvasNode n) {
        String timing = n.finallyWhen();
        if (timing == null || timing.isBlank() || WHEN_ALWAYS.equals(timing)) {
            return null;
        }
        WhenExpression when = new WhenExpression();
        when.setInput(AGGREGATE_STATUS);
        if (WHEN_SUCCEEDED.equals(timing)) {
            when.setOperator("in");
            when.setValues(List.of("Succeeded"));
        } else if (WHEN_FAILED.equals(timing)) {
            when.setOperator("notin");
            when.setValues(List.of("Succeeded", "None"));
        } else {
            throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID,
                    "节点 " + n.id() + " 的收尾时机认不出来：" + timing
                            + "（只能是 " + WHEN_ALWAYS + " / " + WHEN_FAILED + " / " + WHEN_SUCCEEDED + "）");
        }
        return when;
    }

    /**
     * 节点的显示名（用户起的，可中文）。没起名（null / 空 / 只有空白）返回 {@code null} ——
     * 于是**不写 displayName**，渲染结果与加这个字段之前逐字节一致（老画布渲染出来不会变样）。
     */
    private static String displayNameOf(PipelineCanvas.CanvasNode n) {
        String name = n.name();
        return name == null || name.isBlank() ? null : name.trim();
    }

    /**
     * task 的 workspace **绑定**：只写 {@code name} —— Tekton 的
     * {@code getTaskrunWorkspaces} 在 {@code workspace} 字段为空时就用这个名字去 pipeline 的
     * workspace 里找同名的那个。声明里的挂载点（{@code mountPath}）只影响 Pod 里的挂载路径。
     */
    private static WorkspacePipelineTaskBinding taskWorkspaceBinding(WorkspaceDeclaration decl) {
        WorkspacePipelineTaskBinding binding = new WorkspacePipelineTaskBinding();
        binding.setName(decl.getName());
        return binding;
    }

    private static WorkspaceDeclaration declaration(String name, String mountPath) {
        WorkspaceDeclaration wd = new WorkspaceDeclaration();
        wd.setName(name);
        if (mountPath != null) {
            // 挂载点是**约定**：节点脚本里写死了 /creds/... —— 改这里要同时改那两个节点
            wd.setMountPath(mountPath);
        }
        return wd;
    }

    private static PipelineWorkspaceDeclaration pipelineWorkspace(String name) {
        PipelineWorkspaceDeclaration ws = new PipelineWorkspaceDeclaration();
        ws.setName(name);
        return ws;
    }

    /**
     * 环检测（DFS 三色）。**必须做** —— Tekton 侧对环的处理不是我们想要的失败形态，
     * 而且一个带环的画布根本表达不了「谁先跑」。
     */
    private static void rejectCycles(Set<String> ids, List<PipelineCanvas.CanvasEdge> edges) {
        Map<String, List<String>> adj = new LinkedHashMap<>();
        for (String id : ids) {
            adj.put(id, new ArrayList<>());
        }
        for (PipelineCanvas.CanvasEdge e : edges) {
            adj.get(e.from()).add(e.to());
        }
        Map<String, Integer> color = new LinkedHashMap<>();   // 0 白 1 灰 2 黑
        for (String id : ids) {
            color.put(id, 0);
        }
        for (String id : ids) {
            if (color.get(id) == 0 && hasCycle(id, adj, color)) {
                throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID,
                        "流水线里有一个环（节点 " + id + " 回到了自己）—— 节点之间的连线必须是一条有向无环图");
            }
        }
    }

    private static boolean hasCycle(String id, Map<String, List<String>> adj, Map<String, Integer> color) {
        color.put(id, 1);
        for (String next : adj.getOrDefault(id, List.of())) {
            Integer c = color.get(next);
            if (c != null && c == 1) {
                return true;
            }
            if (c != null && c == 0 && hasCycle(next, adj, color)) {
                return true;
            }
        }
        color.put(id, 2);
        return false;
    }
}
