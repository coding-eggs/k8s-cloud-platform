package com.coding.k8sserver.app;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.app.AppInjectDTO;
import com.coding.common.models.app.AppNodeDTO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把节点参数翻译成 chart values。
 *
 * <p>环境变量分两种：普通值 → ConfigMap；引用已有 Secret → {@code valueFrom.secretKeyRef}。
 * **平台不接受明文敏感值**：明文一旦进 spec 就会跟着进 revision 快照与渲染清单，与「密码不落台账」的
 * 硬约束冲突；要敏感就先在「Secret」页建好再引用。
 *
 * <p>WORKER 节点强制关掉持久化与暴露：它没有 Service，也没有卷挂载点，留着只会生成无效字段。
 */
public final class AppWorkloadValues {

    private AppWorkloadValues() {
    }

    public static Map<String, Object> build(String appId, String slug, int revision, AppNodeDTO node) {
        return build(appId, slug, revision, node, List.of());
    }

    public static Map<String, Object> build(String appId, String slug, int revision, AppNodeDTO node,
                                            List<AppInjectDTO> inject) {
        Map<String, Object> params = node.getParams() == null ? Map.of() : node.getParams();
        String image = str(params.get("image"));
        if (image.isBlank()) {
            throw new CloudPlatformException(EnumResponseType.ERROR, "请填写镜像：" + node.getKey());
        }
        boolean worker = "WORKER".equalsIgnoreCase(node.getKind());
        assertSecretRefs(params, node.getKey());
        String mode = worker ? "worker" : "service";

        Map<String, Object> workload = new LinkedHashMap<>();
        workload.put("key", node.getKey());
        workload.put("mode", mode);
        workload.put("image", image);
        workload.put("imagePullPolicy", strOrDefault(params.get("imagePullPolicy"), "IfNotPresent"));
        workload.put("port", num(params.get("port"), 8080));
        workload.put("replicas", num(params.get("replicas"), 1));
        workload.put("command", strings(params.get("command")));
        workload.put("args", strings(params.get("args")));
        List<Map<String, Object>> env = mergedEnv(params, inject);
        List<Map<String, Object>> secretEnv = mergedSecretEnv(params, inject);
        List<Map<String, Object>> inlineEnv = inlineEnv(inject);
        workload.put("env", env);
        workload.put("secretEnv", secretEnv);
        workload.put("inlineEnv", inlineEnv);
        workload.put("imagePullSecrets", strings(params.get("imagePullSecrets")));
        List<Map<String, Object>> files = configFiles(params);
        workload.put("configFiles", files);
        // 只覆盖「会进容器的配置」，不含 revision：revision 每次发布都变，会让没改配置的发布也滚动。
        // 配置文件以 subPath 挂载，ConfigMap 改了容器里也不会更新，必须靠这个哈希滚动 Pod
        workload.put("configHash", configHash(env, secretEnv, inlineEnv, files));
        workload.put("resources", resources(params));
        workload.put("persistence", persistence(params, worker));
        workload.put("autoscaling", autoscaling(params));
        // 默认开：HTTP 服务需要探针；非 HTTP 服务由用户在节点里关掉
        workload.put("probe", Map.of(
                "enabled", !Boolean.FALSE.equals(params.get("probeEnabled")),
                "type", "tcp".equals(params.get("probeType")) ? "tcp" : "http",
                "path", strOrDefault(params.get("probePath"), "/")));
        workload.put("metrics", Map.of(
                "enabled", bool(params.get("metricsEnabled")),
                "path", strOrDefault(params.get("metricsPath"), "/metrics")));
        // 通用注入参数（platform-api 由 desired.otel / desired.mesh 翻译而来，这里只透传，不做业务判断）。
        // 不进 configHash：都在 Pod 模板 / Service 上，改了自然滚动；进哈希会让存量服务升级时无谓重启
        workload.put("podAnnotations", stringMap(params.get("podAnnotations")));
        workload.put("podLabels", stringMap(params.get("podLabels")));
        workload.put("serviceLabels", stringMap(params.get("serviceLabels")));
        workload.put("extraEnv", nameValues(params.get("extraEnv")));
        String logMountDir = str(params.get("logMountDir"));
        workload.put("logVolume", Map.of("enabled", !logMountDir.isBlank(), "mountDir", logMountDir));
        String exposeType = worker ? "none" : strOrDefault(params.get("expose"), "none").toLowerCase();
        workload.put("expose", Map.of(
                "type", exposeType,
                "port", num(params.get("port"), 8080),
                // 0 = 由集群自动分配；只在 nodeport 时有意义
                "nodePort", "nodeport".equals(exposeType) ? num(params.get("nodePort"), 0) : 0));

        workload.put("route", route(params, worker));

        Map<String, Object> app = new LinkedHashMap<>();
        app.put("id", appId);
        app.put("slug", slug);
        app.put("revision", revision);
        return Map.of("app", app, "workload", workload);
    }

    /**
     * 网关端口池暴露：{@code params.route = {kind: http|tcp, parentName, sectionName}}（platform-api 分配端口后生成）。
     * 路由挂到命名空间 ListenerSet 的某个 listener 上，后端是本服务的 ClusterIP Service；
     * 后台进程没有 Service，不生成。
     */
    private static Map<String, Object> route(Map<String, Object> params, boolean worker) {
        if (worker || !(params.get("route") instanceof Map<?, ?> r)) {
            return Map.of("enabled", false);
        }
        String kind = str(r.get("kind")).toLowerCase();
        String parentName = str(r.get("parentName"));
        String sectionName = str(r.get("sectionName"));
        if (!("http".equals(kind) || "tcp".equals(kind)) || parentName.isBlank() || sectionName.isBlank()) {
            return Map.of("enabled", false);
        }
        return Map.of("enabled", true, "kind", kind, "parentName", parentName,
                "sectionName", sectionName, "port", num(params.get("port"), 8080),
                "canary", canary(r.get("canary"), "tcp".equals(kind)));
    }

    /**
     * 入口灰度后端：{@code route.canary = {service, weight, header: {name, value} | null}}（platform-api 生成）。
     * 权重截到 0-100；TCP 没有 header 匹配，忽略 header。
     */
    private static Map<String, Object> canary(Object raw, boolean tcp) {
        if (!(raw instanceof Map<?, ?> c) || str(c.get("service")).isBlank()) {
            return Map.of("enabled", false);
        }
        int weight = Math.max(0, Math.min(100, num(c.get("weight"), 0)));
        String headerName = "";
        String headerValue = "";
        if (!tcp && c.get("header") instanceof Map<?, ?> h) {
            headerName = str(h.get("name"));
            headerValue = str(h.get("value"));
        }
        return Map.of("enabled", true, "service", str(c.get("service")), "weight", weight,
                "headerName", headerName, "headerValue", headerValue);
    }

    /** 用户手填的普通变量 + 依赖注入的普通值。 */
    static List<Map<String, Object>> mergedEnv(Map<String, Object> params, List<AppInjectDTO> inject) {
        List<Map<String, Object>> out = new ArrayList<>(plainEnv(params));
        for (AppInjectDTO d : inject == null ? List.<AppInjectDTO>of() : inject) {
            if (!d.isInline() && (d.getSecretName() == null || d.getSecretName().isBlank())) {
                out.add(Map.of("name", d.getName(), "value", d.getValue() == null ? "" : d.getValue()));
            }
        }
        return out;
    }

    /** 含 {@code $(_REF_n)} 的拼接值：直接写进容器 env，且必须排在 secretEnv 之后（K8s 只展开先定义的变量）。 */
    static List<Map<String, Object>> inlineEnv(List<AppInjectDTO> inject) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AppInjectDTO d : inject == null ? List.<AppInjectDTO>of() : inject) {
            if (d.isInline()) {
                out.add(Map.of("name", d.getName(), "value", d.getValue() == null ? "" : d.getValue()));
            }
        }
        return out;
    }

    /**
     * 配置文件 → {@code [{key, path, content}]}，key 作 {@code <name>-files} ConfigMap 的键与 subPath。
     * 路径与内容由 platform-api 校验并替换好引用，这里只编号。
     */
    static List<Map<String, Object>> configFiles(Map<String, Object> params) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(params.get("configFiles") instanceof List<?> list)) {
            return out;
        }
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                out.add(Map.of("key", "file-" + out.size(), "path", str(m.get("path")),
                        "content", str(m.get("content"))));
            }
        }
        return out;
    }

    private static String configHash(List<Map<String, Object>> env, List<Map<String, Object>> secretEnv,
                                     List<Map<String, Object>> inlineEnv, List<Map<String, Object>> files) {
        StringBuilder sb = new StringBuilder();
        append(sb, "env", env);
        append(sb, "secretEnv", secretEnv);
        append(sb, "inlineEnv", inlineEnv);
        if (!files.isEmpty()) {
            append(sb, "files", files);   // 没有配置文件时哈希与之前一致，不让既有服务因升级而滚动
        }
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d).substring(0, 32);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void append(StringBuilder sb, String tag, List<Map<String, Object>> list) {
        sb.append(tag).append('[');
        for (Map<String, Object> m : list) {
            // 长度前缀，避免「a|bc」与「ab|c」拼出同一串
            m.keySet().stream().sorted().forEach(k -> {
                String v = String.valueOf(m.get(k));
                sb.append(k).append('=').append(v.length()).append(':').append(v).append(';');
            });
            sb.append('|');
        }
        sb.append(']');
    }

    /** 用户引用的 Secret + 依赖注入的凭据引用；都不带口令本体。 */
    static List<Map<String, Object>> mergedSecretEnv(Map<String, Object> params, List<AppInjectDTO> inject) {
        List<Map<String, Object>> out = new ArrayList<>(secretRefs(params));
        for (AppInjectDTO d : inject == null ? List.<AppInjectDTO>of() : inject) {
            if (d.getSecretName() != null && !d.getSecretName().isBlank()) {
                out.add(Map.of("name", d.getName(), "secretName", d.getSecretName(),
                        "secretKey", d.getSecretKey() == null ? d.getName() : d.getSecretKey()));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> plainEnv(Map<String, Object> params) {
        return envList(params).stream()
                .filter(e -> !bool(e.get("secret")))
                .map(e -> Map.<String, Object>of("name", str(e.get("name")), "value", str(e.get("value"))))
                .toList();
    }

    /** 引用已有 Secret 的条目：只有名字与键，没有值。 */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> secretRefs(Map<String, Object> params) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> e : envList(params)) {
            if (!bool(e.get("secret"))) {
                continue;
            }
            out.add(Map.of(
                    "name", str(e.get("name")),
                    "secretName", str(e.get("secretName")),
                    "secretKey", strOrDefault(e.get("secretKey"), str(e.get("name")))));
        }
        return out;
    }

    /** 勾了「引用 Secret」却没填 Secret 名 → 直接报错，别静默变成一个空值的普通变量。 */
    private static void assertSecretRefs(Map<String, Object> params, String nodeKey) {
        for (Map<String, Object> e : envList(params)) {
            if (bool(e.get("secret")) && str(e.get("secretName")).isBlank()) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "环境变量 " + str(e.get("name")) + " 引用了 Secret 但没填 Secret 名（节点 " + nodeKey + "）");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> envList(Map<String, Object> params) {
        Object raw = params.get("env");
        return raw instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private static Map<String, String> stringMap(Object raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                if (k != null && v != null) {
                    out.put(String.valueOf(k), String.valueOf(v));
                }
            });
        }
        return out;
    }

    private static List<Map<String, Object>> nameValues(Object raw) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("name") != null) {
                    out.add(Map.of("name", String.valueOf(m.get("name")),
                            "value", m.get("value") == null ? "" : String.valueOf(m.get("value"))));
                }
            }
        }
        return out;
    }

    private static Map<String, Object> resources(Map<String, Object> params) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("requests", Map.of(
                "cpu", strOrDefault(params.get("cpuRequest"), "100m"),
                "memory", strOrDefault(params.get("memoryRequest"), "128Mi")));
        r.put("limits", Map.of(
                "cpu", strOrDefault(params.get("cpuLimit"), "1"),
                "memory", strOrDefault(params.get("memoryLimit"), "1Gi")));
        return r;
    }

    private static Map<String, Object> persistence(Map<String, Object> params, boolean worker) {
        return Map.of(
                "enabled", !worker && bool(params.get("persistenceEnabled")),
                "size", strOrDefault(params.get("storageSize"), "1Gi"),
                "storageClass", strOrDefault(params.get("storageClass"), ""),
                "mountPath", strOrDefault(params.get("mountPath"), "/data"));
    }

    private static Map<String, Object> autoscaling(Map<String, Object> params) {
        return Map.of(
                "enabled", bool(params.get("autoscalingEnabled")),
                "minReplicas", num(params.get("minReplicas"), 1),
                "maxReplicas", num(params.get("maxReplicas"), 10),
                "targetCpu", num(params.get("targetCpu"), 60),
                // 0 = 不加内存指标（存量快照没有这个键，渲染结果不变）
                "targetMemory", num(params.get("targetMemory"), 0));
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String strOrDefault(Object o, String def) {
        String s = str(o);
        return s.isBlank() ? def : s;
    }

    private static int num(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    private static boolean bool(Object o) {
        return Boolean.TRUE.equals(o);
    }
}
