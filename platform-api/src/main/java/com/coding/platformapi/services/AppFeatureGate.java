package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.WaypointRefDTO;
import com.coding.data.models.system.PlatformApplication;
import com.coding.platformapi.app.AppServiceFeatures;
import com.coding.platformapi.app.AppSnapshot;
import com.coding.platformapi.models.ComponentFlagDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 应用服务的组件门禁与 waypoint 校验（spec 2026-11-07 §2 / §5）。
 *
 * <ul>
 *   <li>保存：只拦「从关到开」（{@link AppServiceFeatures#newlyEnabled}），判据与报错走 {@link ComponentFeatureGuard}；</li>
 *   <li>发布（预览 / 创建）：勾选且相对线上新开启的功能不可用 → 拦；其余生效中但组件已不可用 → 警告（不追溯）；</li>
 *   <li>回滚不调本类：回滚是止血手段，不能被门禁挡住（前端确认框自己列警告）。</li>
 * </ul>
 *
 * <p>waypoint 查询本身失败（集群断开、租户 RBAC 未覆盖 gateway group）放行并记日志，与工作负载页
 * {@code WorkloadService.requireWaypointTargetable} 同口径；查得到就必须拦。
 */
@Slf4j
@Service
public class AppFeatureGate {

    public record Report(List<String> problems, List<String> warnings) {
    }

    private final ComponentFeatureGuard guard;
    private final ClusterComponentService components;
    private final GatewayService gateways;

    public AppFeatureGate(ComponentFeatureGuard guard, ClusterComponentService components, GatewayService gateways) {
        this.guard = guard;
        this.components = components;
        this.gateways = gateways;
    }

    public void assertCanEnable(String clusterId, Map<String, Object> before, Map<String, Object> after) {
        for (String code : AppServiceFeatures.newlyEnabled(before, after)) {
            guard.require(clusterId, code, "开启" + AppServiceFeatures.LABELS.getOrDefault(code, code));
        }
    }

    public void assertWaypoint(String tenantId, String clusterId, String namespace, Map<String, Object> after) {
        String name = AppServiceFeatures.waypointName(after);
        if (name == null) {
            return;
        }
        List<WaypointRefDTO> candidates;
        try {
            candidates = gateways.waypointRefs(tenantId, clusterId, namespace);
        } catch (Exception e) {
            log.warn("校验 waypoint「{}」失败（放行）：{}", name, e.getMessage());
            return;
        }
        String problem = waypointProblem(name, namespace, candidates);
        if (problem != null) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, problem);
        }
    }

    public Report release(PlatformApplication app, AppSnapshot live, AppSnapshot next, Collection<String> selected) {
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, ComponentFlagDTO> flags = null;
        List<WaypointRefDTO> candidates = null;
        boolean candidatesLoaded = false;
        for (Map.Entry<String, Map<String, Object>> e : next.services().entrySet()) {
            String key = e.getKey();
            Map<String, Object> desired = e.getValue();
            boolean picked = selected.contains(key);
            var required = AppServiceFeatures.required(desired);
            if (!required.isEmpty()) {
                if (flags == null) {
                    flags = components.flags(app.getClusterId());
                }
                var newly = picked ? AppServiceFeatures.newlyEnabled(live.services().get(key), desired) : java.util.Set.of();
                for (String code : required) {
                    ComponentFlagDTO flag = flags.get(code);
                    if (flag != null && flag.available()) {
                        continue;
                    }
                    String reason = flag == null || flag.reason() == null || flag.reason().isBlank()
                            ? "依赖的集群组件未安装或未就绪" : flag.reason();
                    String label = AppServiceFeatures.LABELS.getOrDefault(code, code);
                    if (newly.contains(code)) {
                        problems.add("服务 " + key + "：无法开启" + label + "：" + reason);
                    } else {
                        warnings.add("服务 " + key + "：" + label + "依赖的组件当前不可用（" + reason + "），发布后不生效");
                    }
                }
            }
            String wp = AppServiceFeatures.waypointName(desired);
            if (wp != null) {
                if (!candidatesLoaded) {
                    candidatesLoaded = true;
                    try {
                        candidates = gateways.waypointRefs(app.getTenantId(), app.getClusterId(), app.getNamespace());
                    } catch (Exception ex) {
                        log.warn("发布前校验 waypoint 失败（跳过）：{}", ex.getMessage());
                    }
                }
                String problem = candidates == null ? null : waypointProblem(wp, app.getNamespace(), candidates);
                if (problem != null) {
                    (picked ? problems : warnings).add("服务 " + key + "：" + problem);
                }
            }
        }
        return new Report(problems, warnings);
    }

    private static String waypointProblem(String name, String namespace, List<WaypointRefDTO> candidates) {
        WaypointRefDTO hit = candidates.stream().filter(r -> name.equals(r.getName())).findFirst().orElse(null);
        if (hit == null) {
            return "命名空间「" + namespace + "」下不存在 waypoint「" + name + "」"
                    + (candidates.isEmpty() ? "（该命名空间还没有 waypoint）"
                    : "，现有：" + candidates.stream().map(WaypointRefDTO::getName).collect(Collectors.joining("、")));
        }
        if (!GatewayService.canHandleService(hit.getWaypointFor())) {
            return "waypoint「" + name + "」的 " + GatewayService.WAYPOINT_FOR_LABEL + " 是「" + hit.getWaypointFor()
                    + "」，不处理发往服务的流量（istio 会静默放行、L7 不生效）；请选 service 或 all 类型的 waypoint";
        }
        return null;
    }
}
