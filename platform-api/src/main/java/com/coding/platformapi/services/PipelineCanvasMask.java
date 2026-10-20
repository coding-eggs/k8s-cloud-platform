package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
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
        java.util.Map<String, List<String>> next = new java.util.LinkedHashMap<>();
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
