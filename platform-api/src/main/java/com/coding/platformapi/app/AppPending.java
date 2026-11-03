package com.coding.platformapi.app;

import com.coding.data.models.system.PlatformAppDependency;
import com.coding.data.models.system.PlatformAppService;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 待发布改动 = 期望配置 与 线上快照同名服务 的差异，不单独存储。
 * 只比较服务级字段（镜像、副本、变量、资源……）；渲染对象的增删在预览里另算。
 */
public final class AppPending {

    /** type：ADDED / CHANGED / DELETED；fields：CHANGED 时变化的顶层字段名 */
    public record Change(String key, String type, List<String> fields) {
    }

    private AppPending() {
    }

    public static List<Change> compute(Map<String, Map<String, Object>> live, List<PlatformAppService> rows) {
        return compute(live, rows, Set.of());
    }

    /**
     * 同上，另把「引用的依赖连接信息与线上快照不同」也算作改动（字段名 {@code 依赖 <key>}）。
     * 发布总是按当前依赖台账整份渲染线上服务，所以这类服务即使期望配置没变，下次发布也会被改到 ——
     * 必须显式列为待发布，由用户勾选。
     */
    public static List<Change> compute(Map<String, Map<String, Object>> live, List<PlatformAppService> rows,
                                       Set<String> changedDependencies) {
        List<Change> out = new ArrayList<>();
        for (PlatformAppService row : rows) {
            boolean deployed = live.containsKey(row.getServiceKey());
            if (Boolean.TRUE.equals(row.getPendingDelete())) {
                if (deployed) {
                    out.add(new Change(row.getServiceKey(), "DELETED", List.of()));
                }
                continue;   // 从未发布过的待删除行没有任何可发布内容
            }
            if (!deployed) {
                out.add(new Change(row.getServiceKey(), "ADDED", List.of()));
                continue;
            }
            Map<String, Object> desired = AppSnapshot.parseMap(row.getDesiredJson());
            List<String> fields = changedFields(live.get(row.getServiceKey()), desired);
            if (!changedDependencies.isEmpty()) {
                new TreeSet<>(AppServiceDesired.references(desired)).stream()
                        .filter(changedDependencies::contains)
                        .forEach(dep -> fields.add(dependencyField(dep)));
            }
            if (!fields.isEmpty()) {
                out.add(new Change(row.getServiceKey(), "CHANGED", fields));
            }
        }
        // 线上有、台账里没有行：孤儿服务（例如首次发布进行中被删了行），按待删除算，否则永远删不掉
        Set<String> rowKeys = new LinkedHashSet<>();
        rows.forEach(r -> rowKeys.add(r.getServiceKey()));
        for (String liveKey : live.keySet()) {
            if (!rowKeys.contains(liveKey)) {
                out.add(new Change(liveKey, "DELETED", List.of()));
            }
        }
        return out;
    }

    public static String dependencyField(String depKey) {
        return "依赖 " + depKey;
    }

    /**
     * 连接信息与线上快照不同的外部依赖 key（只比白名单字段）。中间件依赖的连接信息发布时现取、实例不可换，不在此列；
     * 线上快照里没有的依赖（新加的）不算：引用它的服务本身就有期望配置改动。
     */
    public static Set<String> changedDependencies(List<PlatformAppDependency> live,
                                                  List<PlatformAppDependency> current) {
        Map<String, PlatformAppDependency> now = new HashMap<>();
        current.forEach(d -> now.put(d.getDepKey(), d));
        Set<String> out = new TreeSet<>();
        for (PlatformAppDependency old : live) {
            PlatformAppDependency cur = now.get(old.getDepKey());
            if (cur == null || !"EXTERNAL".equals(old.getType()) || !"EXTERNAL".equals(cur.getType())) {
                continue;
            }
            if (!Objects.equals(AppSnapshot.scrubExternal(old.getExternalJson()),
                    AppSnapshot.scrubExternal(cur.getExternalJson()))) {
                out.add(old.getDepKey());
            }
        }
        return out;
    }

    public static List<String> changedFields(Map<String, Object> before, Map<String, Object> after) {
        Set<String> keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        List<String> out = new ArrayList<>();
        for (String k : keys) {
            JsonNode a = AppSnapshot.JSON.valueToTree(before.get(k));
            JsonNode b = AppSnapshot.JSON.valueToTree(after.get(k));
            if (!a.equals(b)) {
                out.add(k);
            }
        }
        return out;
    }
}
