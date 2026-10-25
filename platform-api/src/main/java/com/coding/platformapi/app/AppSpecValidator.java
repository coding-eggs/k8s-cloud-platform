package com.coding.platformapi.app;

import com.coding.common.models.app.AppEdgeDTO;
import com.coding.common.models.app.AppNodeDTO;
import com.coding.common.models.app.AppProblemDTO;
import com.coding.common.models.app.AppSpecDTO;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 图的静态校验（纯函数，无 Spring 依赖）。
 *
 * <p>为什么在这里就把环判掉：部署是分阶段推进的，环意味着「谁都得等对方先就绪」，
 * 一旦放过会在 COMPONENTS 阶段卡到超时，而那时的错误信息已经离用户的图很远了。
 */
public final class AppSpecValidator {

    public static final int MAX_NODES = 20;

    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9-]{0,19}$");

    /** v1 的节点类型；CronJob 平台不支持，遇到即报错（不静默忽略）。 */
    private static final Set<String> KINDS = Set.of("SERVICE", "WORKER");

    private AppSpecValidator() {
    }

    public static List<AppProblemDTO> validate(AppSpecDTO spec) {
        List<AppProblemDTO> out = new ArrayList<>();
        if (spec == null) {
            out.add(AppProblemDTO.error(null, "应用定义为空"));
            return out;
        }
        if (spec.getNamespace() == null || spec.getNamespace().isBlank()) {
            out.add(AppProblemDTO.error(null, "未选择命名空间"));
        }
        List<AppNodeDTO> nodes = spec.getNodes() == null ? List.of() : spec.getNodes();
        if (nodes.isEmpty()) {
            out.add(AppProblemDTO.error(null, "应用里至少要有一个节点"));
        }
        if (nodes.size() > MAX_NODES) {
            out.add(AppProblemDTO.error(null, "一个应用最多 20 个节点"));
        }

        Map<String, AppNodeDTO> byKey = new HashMap<>();
        for (AppNodeDTO n : nodes) {
            String key = n.getKey() == null ? "" : n.getKey();
            if (!KEY.matcher(key).matches()) {
                out.add(AppProblemDTO.error(key,
                        "节点 key 只能用小写字母、数字和连字符，且以字母开头（≤20 字符）：" + key));
            }
            if (byKey.putIfAbsent(key, n) != null) {
                out.add(AppProblemDTO.error(key, "重复的节点 key：" + key));
            }
            String kind = n.getKind() == null ? "" : n.getKind();
            if (!KINDS.contains(kind)) {
                out.add(AppProblemDTO.error(key, "不支持的节点类型：" + kind + "（平台不支持 CronJob）"));
            }
        }

        List<AppEdgeDTO> edges = spec.getEdges() == null ? List.of() : spec.getEdges();
        Map<String, List<String>> outEdges = new HashMap<>();
        for (AppEdgeDTO e : edges) {
            String from = e.getFrom() == null ? "" : e.getFrom();
            String to = e.getTo() == null ? "" : e.getTo();
            if (from.equals(to)) {
                out.add(AppProblemDTO.error(from, "连线不能指向自己：" + from));
                continue;
            }
            if (!byKey.containsKey(from) || !byKey.containsKey(to)) {
                out.add(AppProblemDTO.error(from, "连线指向不存在的节点：" + from + " → " + to));
                continue;
            }
            outEdges.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
        }
        String cycle = firstCycle(byKey.keySet(), outEdges);
        if (cycle != null) {
            out.add(AppProblemDTO.error(null, "检测到循环依赖：" + cycle));
        }
        return out;
    }

    /** DFS 找一条环（返回 {@code a → b → a}），没有环返回 null。 */
    static String firstCycle(Set<String> keys, Map<String, List<String>> outEdges) {
        Set<String> done = new HashSet<>();
        Deque<String> path = new ArrayDeque<>();
        for (String k : keys) {
            String hit = dfs(k, outEdges, done, path, new HashSet<>());
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static String dfs(String node, Map<String, List<String>> outEdges, Set<String> done,
                              Deque<String> path, Set<String> onPath) {
        if (onPath.contains(node)) {
            List<String> cycle = new ArrayList<>(path);
            cycle.add(node);
            return String.join(" → ", cycle);
        }
        if (!done.add(node)) {
            return null;
        }
        onPath.add(node);
        path.addLast(node);
        for (String next : outEdges.getOrDefault(node, List.of())) {
            String hit = dfs(next, outEdges, done, path, onPath);
            if (hit != null) {
                return hit;
            }
        }
        path.removeLast();
        onPath.remove(node);
        return null;
    }
}
