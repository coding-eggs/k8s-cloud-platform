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
        workload.put("env", mergedEnv(params, inject));
        workload.put("secretEnv", mergedSecretEnv(params, inject));
        workload.put("resources", resources(params));
        workload.put("persistence", persistence(params, worker));
        workload.put("autoscaling", autoscaling(params));
        // 默认开：HTTP 服务需要探针；非 HTTP 服务由用户在节点里关掉
        workload.put("probe", Map.of(
                "enabled", !Boolean.FALSE.equals(params.get("probeEnabled")),
                "path", strOrDefault(params.get("probePath"), "/")));
        workload.put("metrics", Map.of(
                "enabled", bool(params.get("metricsEnabled")),
                "path", strOrDefault(params.get("metricsPath"), "/metrics")));
        workload.put("expose", Map.of(
                "type", worker ? "none" : strOrDefault(params.get("expose"), "none"),
                "port", num(params.get("port"), 8080)));

        Map<String, Object> app = new LinkedHashMap<>();
        app.put("id", appId);
        app.put("slug", slug);
        app.put("revision", revision);
        return Map.of("app", app, "workload", workload);
    }

    /** 用户手填的普通变量 + 依赖注入的普通值。 */
    static List<Map<String, Object>> mergedEnv(Map<String, Object> params, List<AppInjectDTO> inject) {
        List<Map<String, Object>> out = new ArrayList<>(plainEnv(params));
        for (AppInjectDTO d : inject == null ? List.<AppInjectDTO>of() : inject) {
            if (d.getSecretName() == null || d.getSecretName().isBlank()) {
                out.add(Map.of("name", d.getName(), "value", d.getValue() == null ? "" : d.getValue()));
            }
        }
        return out;
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
                "targetCpu", num(params.get("targetCpu"), 60));
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
