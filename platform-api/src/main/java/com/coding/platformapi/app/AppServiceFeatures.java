package com.coding.platformapi.app;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 服务期望配置里「依赖集群组件」的功能 → 组件功能旗标码（{@code ClusterComponentService.flags} 的 key）。纯函数。
 *
 * <p>只有让功能<b>生效</b>的取值才算开启：网格的「跟随」与 {@code none} 不需要任何组件（没装 Istio 时无害），
 * 任何时候都可设 —— 与 {@code ComponentFeatureGuard.opensAmbient} 同口径。
 */
public final class AppServiceFeatures {

    public static final String HPA = "hpa-resource-metrics";
    public static final String SERVICE_MONITOR = "servicemonitor";
    public static final String OTEL_COLLECTION = "otel-collection";
    public static final String OTEL_LOGS = "otel-logs";
    public static final String AMBIENT_MESH = "ambient-mesh";

    /** 旗标码 → 人读的功能名（报错与警告用） */
    public static final Map<String, String> LABELS = Map.of(
            HPA, "自动扩缩容",
            SERVICE_MONITOR, "指标采集",
            OTEL_COLLECTION, "链路追踪 / 应用日志接入",
            OTEL_LOGS, "日志采集",
            AMBIENT_MESH, "服务网格");

    private AppServiceFeatures() {
    }

    /** 当前配置下生效中的功能需要哪些旗标 */
    public static Set<String> required(Map<String, Object> desired) {
        Set<String> out = new LinkedHashSet<>();
        if (desired == null) {
            return out;
        }
        if (Boolean.TRUE.equals(desired.get("autoscalingEnabled"))) {
            out.add(HPA);
        }
        if (desired.get("port") != null && Boolean.TRUE.equals(desired.get("metricsEnabled"))) {
            out.add(SERVICE_MONITOR);
        }
        if (desired.get("otel") instanceof Map<?, ?> otel) {
            boolean tracing = Boolean.TRUE.equals(otel.get("tracing"));
            boolean logs = Boolean.TRUE.equals(otel.get("logs"));
            boolean fileLogs = logs && OtelInjection.LOG_MODE_FILE.equals(otel.get("logMode"));
            if (tracing || (logs && !fileLogs)) {
                out.add(OTEL_COLLECTION);
            }
            if (logs) {
                out.add(OTEL_LOGS);
            }
        }
        if ("ambient".equals(dataplaneMode(desired)) || waypointName(desired) != null) {
            out.add(AMBIENT_MESH);
        }
        return out;
    }

    /** 相对 {@code before}（null = 新建）新开启的功能；waypoint 换成另一个名字也算开启 */
    public static Set<String> newlyEnabled(Map<String, Object> before, Map<String, Object> after) {
        Set<String> out = new LinkedHashSet<>(required(after));
        out.removeAll(required(before));
        String wp = waypointName(after);
        if (wp != null && before != null && !wp.equals(waypointName(before)) && waypointName(before) != null) {
            out.add(AMBIENT_MESH);
        }
        return out;
    }

    /** 具体的 waypoint 名；跟随 / none 返回 null */
    public static String waypointName(Map<String, Object> desired) {
        if (desired != null && desired.get("mesh") instanceof Map<?, ?> mesh && mesh.get("useWaypoint") != null) {
            String v = String.valueOf(mesh.get("useWaypoint")).trim();
            return v.isEmpty() || Objects.equals(v, "none") ? null : v;
        }
        return null;
    }

    private static String dataplaneMode(Map<String, Object> desired) {
        return desired.get("mesh") instanceof Map<?, ?> mesh && mesh.get("dataplaneMode") != null
                ? String.valueOf(mesh.get("dataplaneMode")) : null;
    }
}
