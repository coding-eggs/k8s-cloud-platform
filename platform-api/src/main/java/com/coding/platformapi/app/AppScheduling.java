package com.coding.platformapi.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * desired 里的调度与挂载（形状与工作负载页一致：紧凑 affinity、{@code VolumeDef}、挂载点列表）
 * → K8s 原生字段。chart 直接 {@code toYaml}，k8s-server 不再解释平台语义。
 *
 * <p>纯函数；空的分支一律不产出，保证「没填 = 渲染结果与之前逐字相同」。
 */
public final class AppScheduling {

    private AppScheduling() {
    }

    /** 紧凑 affinity → K8s affinity；没有有效内容返回 null */
    public static Map<String, Object> k8sAffinity(Map<String, Object> compact) {
        if (compact == null || compact.isEmpty()) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (compact.get("nodeAffinity") instanceof Map<?, ?> na) {
            Map<String, Object> node = new LinkedHashMap<>();
            if (na.get("required") instanceof Map<?, ?> required) {
                List<Map<String, Object>> terms = new ArrayList<>();
                for (Object o : asList(required.get("nodeSelectorTerms"))) {
                    if (o instanceof Map<?, ?> term) {
                        Map<String, Object> k8s = nodeTerm(term);
                        if (!k8s.isEmpty()) {
                            terms.add(k8s);
                        }
                    }
                }
                if (!terms.isEmpty()) {
                    node.put("requiredDuringSchedulingIgnoredDuringExecution", Map.of("nodeSelectorTerms", terms));
                }
            }
            if (na.get("preferred") instanceof List<?> preferred) {
                List<Map<String, Object>> terms = new ArrayList<>();
                for (Object o : preferred) {
                    if (o instanceof Map<?, ?> p && p.get("preference") instanceof Map<?, ?> preference) {
                        Map<String, Object> k8s = nodeTerm(preference);
                        if (!k8s.isEmpty()) {
                            Map<String, Object> term = new LinkedHashMap<>();
                            term.put("weight", p.get("weight") instanceof Number w ? w.intValue() : 1);
                            term.put("preference", k8s);
                            terms.add(term);
                        }
                    }
                }
                if (!terms.isEmpty()) {
                    node.put("preferredDuringSchedulingIgnoredDuringExecution", terms);
                }
            }
            if (!node.isEmpty()) {
                out.put("nodeAffinity", node);
            }
        }
        podTerms(compact.get("podAffinity"), out, "podAffinity");
        podTerms(compact.get("podAntiAffinity"), out, "podAntiAffinity");
        return out.isEmpty() ? null : out;
    }

    private static void podTerms(Object raw, Map<String, Object> out, String key) {
        if (!(raw instanceof Map<?, ?> m)) {
            return;
        }
        Map<String, Object> k8s = new LinkedHashMap<>();
        if (m.get("required") instanceof List<?> required) {
            List<Map<String, Object>> terms = new ArrayList<>();
            for (Object o : required) {
                Map<String, Object> term = o instanceof Map<?, ?> t ? podTerm(t) : null;
                if (term != null) {
                    terms.add(term);
                }
            }
            if (!terms.isEmpty()) {
                k8s.put("requiredDuringSchedulingIgnoredDuringExecution", terms);
            }
        }
        if (m.get("preferred") instanceof List<?> preferred) {
            List<Map<String, Object>> terms = new ArrayList<>();
            for (Object o : preferred) {
                if (o instanceof Map<?, ?> p && p.get("podAffinityTerm") instanceof Map<?, ?> t) {
                    Map<String, Object> term = podTerm(t);
                    if (term != null) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("weight", p.get("weight") instanceof Number w ? w.intValue() : 1);
                        row.put("podAffinityTerm", term);
                        terms.add(row);
                    }
                }
            }
            if (!terms.isEmpty()) {
                k8s.put("preferredDuringSchedulingIgnoredDuringExecution", terms);
            }
        }
        if (!k8s.isEmpty()) {
            out.put(key, k8s);
        }
    }

    /** 节点亲和的一个 term：只有 matchExpressions / matchFields 有内容才保留（空 term 会废掉整条 required） */
    private static Map<String, Object> nodeTerm(Map<?, ?> term) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> expressions = expressions(term.get("matchExpressions"));
        if (!expressions.isEmpty()) {
            out.put("matchExpressions", expressions);
        }
        List<Map<String, Object>> fields = expressions(term.get("matchFields"));
        if (!fields.isEmpty()) {
            out.put("matchFields", fields);
        }
        return out;
    }

    private static List<Map<String, Object>> expressions(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : asList(raw)) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            String key = str(m.get("key"));
            String operator = str(m.get("operator"));
            if (key.isEmpty()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", key);
            row.put("operator", operator.isEmpty() ? "In" : operator);
            if (!"Exists".equals(operator) && !"DoesNotExist".equals(operator)) {
                row.put("values", strings(m.get("values")));
            }
            out.add(row);
        }
        return out;
    }

    /** Pod 亲和 term：K8s 的匹配写在 labelSelector 里；没有 topologyKey 的 term 无意义，丢掉 */
    private static Map<String, Object> podTerm(Map<?, ?> m) {
        String topologyKey = str(m.get("topologyKey"));
        if (topologyKey.isEmpty()) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (m.get("matchLabels") instanceof Map<?, ?> labels && !labels.isEmpty()) {
            out.put("labelSelector", Map.of("matchLabels", stringMap(labels)));
        }
        out.put("topologyKey", topologyKey);
        List<String> namespaces = strings(m.get("namespaces"));
        if (!namespaces.isEmpty()) {
            out.put("namespaces", namespaces);
        }
        return out;
    }

    /** 卷定义 → chart 的 volumes 项（K8s 原生卷结构） */
    public static List<Map<String, Object>> k8sVolumes(List<Map<String, Object>> volumes) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (volumes == null) {
            return out;
        }
        for (Map<String, Object> v : volumes) {
            String name = str(v.get("name"));
            String type = str(v.get("type"));
            if (name.isEmpty() || !(v.get(type) instanceof Map<?, ?> src)) {
                continue;
            }
            Map<String, Object> source = new LinkedHashMap<>();
            switch (type) {
                case "emptyDir" -> {
                    Map<String, Object> empty = new LinkedHashMap<>();
                    String medium = str(src.get("medium"));
                    if (!medium.isEmpty()) {
                        empty.put("medium", medium);
                    }
                    if (src.get("sizeLimit") instanceof Number n && n.longValue() > 0) {
                        empty.put("sizeLimit", quantity(n.longValue()));
                    }
                    source.put("emptyDir", empty);
                }
                case "configMap" -> {
                    Map<String, Object> cm = new LinkedHashMap<>();
                    cm.put("name", str(src.get("name")));
                    copy("items", src, cm);
                    source.put("configMap", cm);
                }
                case "secret" -> {
                    Map<String, Object> secret = new LinkedHashMap<>();
                    secret.put("secretName", str(src.get("secretName")));
                    copy("items", src, secret);
                    source.put("secret", secret);
                }
                case "persistentVolumeClaim" ->
                        source.put("persistentVolumeClaim", Map.of("claimName", str(src.get("claimName"))));
                case "hostPath" -> {
                    Map<String, Object> host = new LinkedHashMap<>();
                    host.put("path", str(src.get("path")));
                    String pathType = str(src.get("type"));
                    if (!pathType.isEmpty()) {
                        host.put("type", pathType);
                    }
                    source.put("hostPath", host);
                }
                case "projected" -> source.put("projected", cleaned(src));
                case "downwardAPI" -> source.put("downwardAPI", cleaned(src));
                case "csi" -> source.put("csi", cleaned(src));
                case "nfs" -> {
                    Map<String, Object> nfs = new LinkedHashMap<>();
                    nfs.put("server", str(src.get("server")));
                    nfs.put("path", str(src.get("path")));
                    if (Boolean.TRUE.equals(src.get("readOnly"))) {
                        nfs.put("readOnly", true);
                    }
                    source.put("nfs", nfs);
                }
                default -> {
                    continue;
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.putAll(source);
            out.add(row);
        }
        return out;
    }

    /** 挂载点直接就是 K8s 的 volumeMounts 形状 */
    public static List<Map<String, Object>> k8sVolumeMounts(List<Map<String, Object>> mounts) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (mounts == null) {
            return out;
        }
        for (Map<String, Object> m : mounts) {
            String name = str(m.get("name"));
            String mountPath = str(m.get("mountPath"));
            if (name.isEmpty() || mountPath.isEmpty()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("mountPath", mountPath);
            if (Boolean.TRUE.equals(m.get("readOnly"))) {
                row.put("readOnly", true);
            }
            String subPath = str(m.get("subPath"));
            if (!subPath.isEmpty()) {
                row.put("subPath", subPath);
            }
            out.add(row);
        }
        return out;
    }

    /** 字节 → K8s quantity（整 Gi / Mi 用后缀，否则按字节写） */
    static String quantity(long bytes) {
        if (bytes % (1L << 30) == 0) {
            return (bytes >> 30) + "Gi";
        }
        if (bytes % (1L << 20) == 0) {
            return (bytes >> 20) + "Mi";
        }
        if (bytes % (1L << 10) == 0) {
            return (bytes >> 10) + "Ki";
        }
        return String.valueOf(bytes);
    }

    /** 原样透传的子对象：去掉 null 与空集合（YAML 里显式 null 没有意义，还可能被 API 校验拒绝） */
    private static Map<String, Object> cleaned(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            Object value = clean(v);
            if (value != null) {
                out.put(String.valueOf(k), value);
            }
        });
        return out;
    }

    private static Object clean(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> out = cleaned(m);
            return out.isEmpty() ? null : out;
        }
        if (raw instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object o : list) {
                Object value = clean(o);
                if (value != null) {
                    out.add(value);
                }
            }
            return out.isEmpty() ? null : out;
        }
        return raw;
    }

    private static void copy(String key, Map<?, ?> from, Map<String, Object> to) {
        if (from.get(key) instanceof List<?> list && !list.isEmpty()) {
            to.put(key, list);
        }
    }

    private static List<?> asList(Object raw) {
        return raw instanceof List<?> list ? list : List.of();
    }

    private static List<String> strings(Object raw) {
        List<String> out = new ArrayList<>();
        for (Object o : asList(raw)) {
            if (o != null) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    private static Map<String, String> stringMap(Map<?, ?> m) {
        Map<String, String> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
