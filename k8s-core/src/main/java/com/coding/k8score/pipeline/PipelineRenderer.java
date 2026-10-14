package com.coding.k8score.pipeline;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import io.fabric8.tekton.v1.EmbeddedTask;
import io.fabric8.tekton.v1.PipelineSpec;
import io.fabric8.tekton.v1.PipelineTask;
import io.fabric8.tekton.v1.PipelineWorkspaceDeclaration;
import io.fabric8.tekton.v1.TaskSpec;
import io.fabric8.tekton.v1.WorkspaceDeclaration;

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
 * 同一份画布渲染两次结果字节等价。
 */
public final class PipelineRenderer {

    private final PipelineNodeRegistry nodes;

    public PipelineRenderer(PipelineNodeRegistry nodes) {
        this.nodes = nodes;
    }

    /**
     * @param workspaceClaimName 隐式共享 workspace 的名字。平台注入到**所有**节点，用户不感知
     */
    public PipelineSpec render(PipelineCanvas canvas, String workspaceClaimName) {
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
            if (!byId.containsKey(e.from())) {
                throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID,
                        "连线指向了不存在的节点：" + e.from());
            }
            if (!byId.containsKey(e.to())) {
                throw new CloudPlatformException(EnumResponseType.PIPELINE_INVALID,
                        "连线指向了不存在的节点：" + e.to());
            }
            runAfter.computeIfAbsent(e.to(), k -> new LinkedHashSet<>()).add(e.from());
        }
        rejectCycles(byId.keySet(), edges);

        List<PipelineTask> tasks = new ArrayList<>();
        for (PipelineCanvas.CanvasNode n : canvas.nodes()) {
            PipelineNode impl = nodes.of(n.type());
            NodeContext ctx = new NodeContext(n.id(), n.id(), workspaceClaimName, n);
            RenderedStep rendered = impl.render(ctx);

            TaskSpec taskSpec = new TaskSpec();
            taskSpec.setSteps(rendered.steps());
            // 隐式共享 workspace：每个 task 都挂上，用户不用管
            WorkspaceDeclaration wd = new WorkspaceDeclaration();
            wd.setName(workspaceClaimName);
            taskSpec.setWorkspaces(List.of(wd));

            // ⚠️ PipelineTask.taskSpec 的类型是 **EmbeddedTask** 而不是 TaskSpec ——
            // fabric8 的模型里 EmbeddedTask 是「内联任务」的外壳（它还有 apiVersion/kind/metadata
            // 三个字段用于引用式写法），真正的 TaskSpec 装在它的 spec 里。
            EmbeddedTask embedded = new EmbeddedTask();
            embedded.setSpec(taskSpec);

            PipelineTask task = new PipelineTask();
            task.setName(n.id());                      // 节点 id 就是 task 名
            task.setTaskSpec(embedded);
            Set<String> after = runAfter.get(n.id());
            if (after != null && !after.isEmpty()) {
                task.setRunAfter(new ArrayList<>(after));
            }
            tasks.add(task);
        }

        PipelineWorkspaceDeclaration ws = new PipelineWorkspaceDeclaration();
        ws.setName(workspaceClaimName);
        PipelineSpec spec = new PipelineSpec();
        spec.setTasks(tasks);
        spec.setWorkspaces(List.of(ws));
        return spec;
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
