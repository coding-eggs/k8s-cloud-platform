package com.coding.platformapi.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务期望配置里的 {@code otel} / {@code mesh} → 渲染参数（纯函数）。
 *
 * <p>k8s-server 零业务：这里把业务语义翻译成通用的 {@code podAnnotations} / {@code podLabels} /
 * {@code serviceLabels} / {@code extraEnv} / {@code logMountDir}，chart 只照着渲染。
 * 没有这两块的服务返回空 Map —— 渲染参数与引入本功能前完全相同（存量服务不滚动）。
 */
public final class AppServiceInjection {

    public static final String LABEL_DATAPLANE_MODE = "istio.io/dataplane-mode";
    public static final String LABEL_USE_WAYPOINT = "istio.io/use-waypoint";

    private AppServiceInjection() {
    }

    /** @param workloadName 工作负载名 {@code <slug>-<key>}，用作 {@code OTEL_SERVICE_NAME} */
    public static Map<String, Object> params(Map<String, Object> desired, String workloadName) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (desired.get("otel") instanceof Map<?, ?> otel) {
            OtelInjection.Plan plan = OtelInjection.plan(
                    otel.get("language") == null ? null : String.valueOf(otel.get("language")),
                    Boolean.TRUE.equals(otel.get("tracing")),
                    Boolean.TRUE.equals(otel.get("logs")),
                    otel.get("logMode") == null ? null : String.valueOf(otel.get("logMode")),
                    otel.get("logFile") == null ? null : String.valueOf(otel.get("logFile")),
                    otel.get("sampling") instanceof Number n ? n.intValue() : null,
                    workloadName);
            if (!plan.podAnnotations().isEmpty()) {
                out.put("podAnnotations", new LinkedHashMap<>(plan.podAnnotations()));
            }
            if (!plan.env().isEmpty()) {
                List<Map<String, Object>> env = new ArrayList<>();
                plan.env().forEach((k, v) -> env.add(Map.of("name", k, "value", v)));
                out.put("extraEnv", env);
            }
            if (plan.logMountDir() != null) {
                out.put("logMountDir", plan.logMountDir());
            }
        }
        if (desired.get("mesh") instanceof Map<?, ?> mesh) {
            if (mesh.get("dataplaneMode") != null) {
                out.put("podLabels", Map.of(LABEL_DATAPLANE_MODE, String.valueOf(mesh.get("dataplaneMode"))));
            }
            if (mesh.get("useWaypoint") != null) {
                out.put("serviceLabels", Map.of(LABEL_USE_WAYPOINT, String.valueOf(mesh.get("useWaypoint"))));
            }
        }
        return out;
    }
}
