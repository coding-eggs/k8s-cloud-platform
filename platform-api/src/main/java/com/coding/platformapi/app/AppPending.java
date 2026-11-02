package com.coding.platformapi.app;

import com.coding.data.models.system.PlatformAppService;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
            List<String> fields = changedFields(live.get(row.getServiceKey()),
                    AppSnapshot.parseMap(row.getDesiredJson()));
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
