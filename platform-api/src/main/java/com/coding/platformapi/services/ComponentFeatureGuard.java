package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.platformapi.models.ComponentFlagDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 「依赖组件的功能，组件没装（或不健康）就不允许开启」的后端硬门禁（2026-11-07 用户定的口径）。
 *
 * <p>判据<b>只有一处</b>：{@link ClusterComponentService#flags} 的功能码（依赖组件全部 INSTALLED_HEALTHY；
 * {@code otel-logs} 另看采集栈是否开了应用日志）。前端置灰读的是同一份旗标，两边口径一致。
 *
 * <p>⚠️ <b>只在「从关到开 / 新建」时调用</b>：已开启的功能允许关闭、不追溯 —— 组件被卸掉后，
 * 用户得能把残留的开关关掉；不涉及这些字段的编辑也不该被一个不相干的组件卡住。
 * 判断「是不是在开启」是各 service 的事（它们知道线上现值），本类只回答「能不能开」。
 *
 * <p>旗标探不到（集群断开等）时组件判为 UNKNOWN → 不可用 → 拒绝（fail-closed）：
 * 开启一个依赖组件的功能，宁可让用户稍后重试，也别写下一个注定不生效的配置。
 */
@Service
@RequiredArgsConstructor
public class ComponentFeatureGuard {

    public static final String AMBIENT_MESH = "ambient-mesh";
    public static final String OTEL_COLLECTION = "otel-collection";
    public static final String OTEL_LOGS = "otel-logs";
    public static final String HPA_RESOURCE_METRICS = "hpa-resource-metrics";
    /** Prometheus Operator 的 CRD（ServiceMonitor / PodMonitor / PrometheusRule）共用这一个功能码 */
    public static final String SERVICE_MONITOR = "servicemonitor";

    private final ClusterComponentService componentService;

    /**
     * 新值相对旧值是不是在「开启」ambient（工作负载 / Service / 命名空间三处共用）。
     *
     * <p>开启 = {@code istio.io/dataplane-mode} 变成 {@code ambient}，或 {@code istio.io/use-waypoint}
     * 换成一个<b>具体的</b> waypoint 名。移除标签、设成 {@code none}（显式退出 / 不使用）、原值不变都不算 ——
     * 这些在没装 Istio 的集群上写下去也无害，且是「关掉残留配置」的唯一途径。
     *
     * @param newMode     null = 本次不动（视同不开启）
     * @param newWaypoint null = 本次不动
     */
    public static boolean opensAmbient(String newMode, String oldMode, String newWaypoint, String oldWaypoint) {
        boolean mode = "ambient".equals(trim(newMode)) && !"ambient".equals(trim(oldMode));
        String wp = trim(newWaypoint);
        boolean waypoint = wp != null && !wp.isEmpty() && !"none".equals(wp) && !wp.equals(trim(oldWaypoint));
        return mode || waypoint;
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    /**
     * @param feature 功能码（稳定 id，见 {@code ClusterComponentCatalog}）
     * @param action  人读的动作名，拼进报错（如「开启链路追踪」）
     */
    public void require(String clusterId, String feature, String action) {
        ComponentFlagDTO flag = componentService.flags(clusterId).get(feature);
        if (flag != null && flag.available()) {
            return;
        }
        String reason = flag == null || flag.reason() == null || flag.reason().isBlank()
                ? "依赖的集群组件未安装或未就绪"
                : flag.reason();
        throw new CloudPlatformException(EnumResponseType.OPERATION_NOT_SUPPORTED,
                "无法" + action + "：" + reason);
    }
}
