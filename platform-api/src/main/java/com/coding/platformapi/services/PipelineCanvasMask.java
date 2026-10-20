package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 画布的**结构规则**：屏蔽闭包与子画布裁剪。
 *
 * <p>为什么在 platform-api：本模块已经在读画布 JSON（{@link PipelineCanvasRefs}），而
 * {@code k8s-core} 的 {@code PipelineCanvas} / 渲染器依赖不到这里。把「哪些节点不跑」在发出去之前
 * 算好，k8s-server 的渲染器就**一行都不用改** —— 它照旧把「节点 + 边」翻成 task / runAfter。
 *
 * <p>⚠️ 只做筛选与一处字段改写（收尾节点作为重试目标时清掉 finallyTask/finallyWhen），
 * 不解析 config、不碰连接。
 */
final class PipelineCanvasMask {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private PipelineCanvasMask() {
    }

    /** 画布上的一个节点（只取规则需要的两个字段）。 */
    record Node(String id, boolean finallyTask) {
    }

    /** 一条连线。 */
    record Edge(String from, String to) {
    }

    record Canvas(List<Node> nodes, List<Edge> edges) {
    }

    /**
     * 屏蔽闭包 = 勾选的节点 ∪ 它们的全部传递下游。
     *
     * <p>下游必须一起屏蔽：下游的输入往往就是被屏蔽节点刚写进共享工作区的产物。
     */
    static Set<String> maskedClosure(String canvasJson, List<String> selectedIds) {
        if (selectedIds == null || selectedIds.isEmpty()) {
            return Set.of();
        }
        Canvas c = parse(canvasJson);
        Set<String> all = idsOf(c);
        for (String id : selectedIds) {
            if (!all.contains(id)) {
                throw invalid("画布上没有这个节点：" + id);
            }
        }
        Set<String> out = new LinkedHashSet<>(selectedIds);
        out.addAll(descendants(out, c.edges(), all));
        return out;
    }

    /**
     * 只保留 {@code keepIds} 里的节点，以及两端都还在的边。
     *
     * @param retryTargetId 收尾节点作为重试目标时传它 —— 清掉 {@code finallyTask} / {@code finallyWhen}，
     *                      否则「只有 finally、没有 tasks」的 PipelineSpec 不成立
     */
    static String trim(String canvasJson, Set<String> keepIds, String retryTargetId) {
        JsonNode root = root(canvasJson);
        if (keepIds == null || keepIds.isEmpty()) {
            throw invalid("裁剪后没有任何节点");
        }
        ArrayNode nodes = JSON.createArrayNode();
        int runnable = 0;
        for (JsonNode n : root.path("nodes")) {
            String id = text(n, "id");
            if (!keepIds.contains(id)) {
                continue;
            }
            JsonNode copy = n.deepCopy();
            if (id.equals(retryTargetId) && copy instanceof ObjectNode obj) {
                obj.remove("finallyTask");
                obj.remove("finallyWhen");
            }
            if (!copy.path("finallyTask").asBoolean(false)) {
                runnable++;
            }
            nodes.add(copy);
        }
        ArrayNode edges = JSON.createArrayNode();
        for (JsonNode e : root.path("edges")) {
            String from = text(e, "from");
            String to = text(e, "to");
            if (keepIds.contains(from) && keepIds.contains(to)) {
                edges.add(e.deepCopy());
            }
        }
        if (runnable == 0) {
            throw invalid("屏蔽后没有可运行的节点了 —— 至少留一个不在收尾里跑的节点");
        }
        ObjectNode out = (ObjectNode) root;
        out.set("nodes", nodes);
        out.set("edges", edges);
        return JSON.writeValueAsString(out);
    }

    /** 这个节点在画布上是不是收尾节点。 */
    static boolean isFinally(String canvasJson, String nodeId) {
        for (Node n : parse(canvasJson).nodes()) {
            if (n.id().equals(nodeId)) {
                return n.finallyTask();
            }
        }
        return false;
    }

    /** 重试计划：可点集合、会执行的节点集合、以及每个不可点节点的原因。 */
    record Plan(Set<String> retryable, Set<String> runNodeIds, Map<String, String> blocked) {
    }

    /**
     * 断点 = 自己没成功、且全部传递上游都已经成功。
     *
     * <p>范围 = 所有断点的子树之并。并行断点必须一起补：Tekton 的停止是整条运行级的，
     * 只补一条会产出「成功但流水线没跑完」的记录，而成功即删工作区 —— 另一条就永远补不了了。
     *
     * @param effectiveStatus 节点 id → 状态；**没有键 = 没轮到它 = 没成功**
     */
    static Plan plan(String canvasJson, Map<String, String> effectiveStatus, Set<String> masked) {
        Canvas c = parse(canvasJson);
        Set<String> all = idsOf(c);
        Map<String, Set<String>> parents = parentsOf(c, all);

        Set<String> frontier = new LinkedHashSet<>();
        for (Node n : c.nodes()) {
            if (n.finallyTask() || masked.contains(n.id()) || succeeded(effectiveStatus, n.id())) {
                continue;
            }
            if (allSucceeded(ancestorsOf(n.id(), parents), effectiveStatus)) {
                frontier.add(n.id());
            }
        }
        Set<String> scope = new LinkedHashSet<>(frontier);
        scope.addAll(descendants(frontier, c.edges(), all));
        scope.removeAll(masked);

        Set<String> retryable = new LinkedHashSet<>(frontier);
        Map<String, String> blocked = new LinkedHashMap<>();
        for (Node n : c.nodes()) {
            if (masked.contains(n.id())) {
                blocked.put(n.id(), "本次运行屏蔽了它（连同下游）");
            } else if (succeeded(effectiveStatus, n.id())) {
                blocked.put(n.id(), "这个节点已经成功，不需要重试");
            } else if (n.finallyTask()) {
                // 收尾不参与连线，上游概念不成立 —— 没跑成就可单独重跑它自己
                retryable.add(n.id());
            } else if (!frontier.contains(n.id())) {
                String up = firstFrontierAncestor(n.id(), parents, frontier);
                blocked.put(n.id(), up == null
                        ? "它的上游还没有全部成功，请从上游开始重试"
                        : "上游 " + up + " 没有成功，请从它开始重试");
            }
        }
        // ⚠️ 不能用 Set.copyOf：它不保序，会让「断点在前、下游在后」这个可读顺序随机化
        return new Plan(java.util.Collections.unmodifiableSet(new LinkedHashSet<>(retryable)),
                java.util.Collections.unmodifiableSet(new LinkedHashSet<>(scope)),
                Map.copyOf(blocked));
    }

    private static Map<String, Set<String>> parentsOf(Canvas c, Set<String> all) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (String id : all) {
            out.put(id, new LinkedHashSet<>());
        }
        for (Edge e : c.edges()) {
            out.get(e.to()).add(e.from());
        }
        return out;
    }

    private static Set<String> ancestorsOf(String id, Map<String, Set<String>> parents) {
        Set<String> out = new LinkedHashSet<>();
        List<String> stack = new ArrayList<>(parents.getOrDefault(id, Set.of()));
        while (!stack.isEmpty()) {
            String cur = stack.remove(stack.size() - 1);
            if (out.add(cur)) {
                stack.addAll(parents.getOrDefault(cur, Set.of()));
            }
        }
        return out;
    }

    private static boolean succeeded(Map<String, String> status, String id) {
        return "SUCCEEDED".equals(status.get(id));
    }

    private static boolean allSucceeded(Set<String> ids, Map<String, String> status) {
        for (String id : ids) {
            if (!succeeded(status, id)) {
                return false;
            }
        }
        return true;
    }

    private static String firstFrontierAncestor(String id, Map<String, Set<String>> parents,
                                                Set<String> frontier) {
        for (String a : ancestorsOf(id, parents)) {
            if (frontier.contains(a)) {
                return a;
            }
        }
        return null;
    }

    // ---- 解析与图遍历（断点计算也复用它们）----

    static Canvas parse(String canvasJson) {
        JsonNode root = root(canvasJson);
        List<Node> nodes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode n : root.path("nodes")) {
            String id = text(n, "id");
            if (id.isBlank()) {
                throw invalid("画布里有节点没有 id");
            }
            if (!seen.add(id)) {
                throw invalid("节点 id 重复：" + id);
            }
            nodes.add(new Node(id, n.path("finallyTask").asBoolean(false)));
        }
        List<Edge> edges = new ArrayList<>();
        for (JsonNode e : root.path("edges")) {
            edges.add(new Edge(text(e, "from"), text(e, "to")));
        }
        return new Canvas(List.copyOf(nodes), List.copyOf(edges));
    }

    static Set<String> idsOf(Canvas c) {
        Set<String> out = new LinkedHashSet<>();
        for (Node n : c.nodes()) {
            out.add(n.id());
        }
        return out;
    }

    /** 从 {@code roots} 出发、沿边正向可达的全部节点（画布已保证无环）。 */
    static Set<String> descendants(Collection<String> roots, List<Edge> edges, Set<String> allIds) {
        Map<String, List<String>> next = new LinkedHashMap<>();
        for (Edge e : edges) {
            next.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to());
        }
        Set<String> out = new LinkedHashSet<>();
        List<String> stack = new ArrayList<>(roots);
        while (!stack.isEmpty()) {
            String cur = stack.remove(stack.size() - 1);
            for (String n : next.getOrDefault(cur, List.of())) {
                if (out.add(n)) {
                    stack.add(n);
                }
            }
        }
        out.retainAll(allIds);
        return out;
    }

    static JsonNode root(String canvasJson) {
        if (canvasJson == null || canvasJson.isBlank()) {
            throw invalid("画布为空");
        }
        try {
            return JSON.readTree(canvasJson);
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.PIPELINE_CANVAS_INVALID, "画布内容无法解析");
        }
    }

    static String text(JsonNode n, String field) {
        String v = n.path(field).asString("");
        return v == null ? "" : v;
    }

    static CloudPlatformException invalid(String message) {
        return new CloudPlatformException(EnumResponseType.PIPELINE_INVALID, message);
    }
}
