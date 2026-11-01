package com.coding.platformapi.app;

import com.coding.data.models.system.PlatformAppDependency;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 发布快照：服务期望配置（引用保持未解析）+ 依赖清单。
 *
 * <p>不含任何口令值：服务侧由 {@link AppServiceDesired} 白名单保证，
 * 外部依赖只留连接地址和 Secret 名/键（{@link #scrubExternal} 白名单之外的字段一律丢掉）。
 */
public record AppSnapshot(Map<String, Map<String, Object>> services, List<PlatformAppDependency> dependencies) {

    public static final ObjectMapper JSON = JsonMapper.builder().build();

    public static AppSnapshot empty() {
        return new AppSnapshot(new TreeMap<>(), List.of());
    }

    @SuppressWarnings("unchecked")
    public static AppSnapshot parse(String json) {
        if (json == null || json.isBlank()) {
            return empty();
        }
        JsonNode root = JSON.readTree(json);
        Map<String, Map<String, Object>> services = new TreeMap<>();
        JsonNode s = root.get("services");
        if (s != null && s.isObject()) {
            for (Map.Entry<String, JsonNode> e : s.properties()) {
                services.put(e.getKey(), JSON.convertValue(e.getValue(), Map.class));
            }
        }
        List<PlatformAppDependency> deps = new ArrayList<>();
        JsonNode d = root.get("dependencies");
        if (d != null && d.isArray()) {
            for (JsonNode n : d) {
                PlatformAppDependency dep = new PlatformAppDependency();
                dep.setDepKey(n.path("key").asString(""));
                dep.setType(n.path("type").asString(""));
                dep.setOwnership(n.hasNonNull("ownership") ? n.get("ownership").asString() : null);
                dep.setInstanceId(n.hasNonNull("instanceId") ? n.get("instanceId").asString() : null);
                if (n.hasNonNull("external")) {
                    dep.setExternalJson(JSON.writeValueAsString(n.get("external")));
                }
                deps.add(dep);
            }
        }
        return new AppSnapshot(services, deps);
    }

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("services", new TreeMap<>(services));
        List<Map<String, Object>> deps = new ArrayList<>();
        for (PlatformAppDependency dep : dependencies) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", dep.getDepKey());
            m.put("type", dep.getType());
            m.put("ownership", dep.getOwnership());
            m.put("instanceId", dep.getInstanceId());
            Map<String, Object> external = scrubExternal(dep.getExternalJson());
            if (external != null) {
                m.put("external", external);
            }
            deps.add(m);
        }
        root.put("dependencies", deps);
        return JSON.writeValueAsString(root);
    }

    /** 只留白名单字段：库里的 external_json 即使被写进了别的东西，也进不了快照。 */
    public static Map<String, Object> scrubExternal(String externalJson) {
        if (externalJson == null || externalJson.isBlank()) {
            return null;
        }
        JsonNode n = JSON.readTree(externalJson);
        Map<String, Object> out = new LinkedHashMap<>();
        for (String f : List.of("protocol", "host", "port", "secretName", "usernameKey", "passwordKey")) {
            JsonNode v = n.get(f);
            if (v != null && !v.isNull()) {
                out.put(f, v.isNumber() ? (Object) v.intValue() : v.asString());
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseMap(String json) {
        return json == null || json.isBlank() ? new LinkedHashMap<>() : JSON.readValue(json, Map.class);
    }
}
