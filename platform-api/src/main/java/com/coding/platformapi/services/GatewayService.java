package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.GatewayDTO;
import com.coding.common.models.k8s.dto.WaypointRefDTO;
import com.coding.platformapi.k8s.K8sClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * Gateway 业务层：六操作透传 + <b>waypoint 判定与「处理哪类流量」投影</b>（平台侧唯一实现）。
 *
 * <h2>判定口径：看 {@code spec.gatewayClassName}</h2>
 * 依据 Istio 源码 {@code pkg/config/constants/constants.go}：
 * <pre>
 *   WaypointGatewayClassName      = "istio-waypoint"
 *   AgentgatewayWaypointClassName = "istio-agentgateway-waypoint"
 * </pre>
 * <b>不</b>靠 {@code istio.io/waypoint-for} 那个 label —— 它只声明这个 waypoint 处理<b>哪类流量</b>
 * （service / workload / all / none），不是"是不是 waypoint"的判据；官方文档的 egress-gateway 示例
 * 就是个不带该 label 的 waypoint。
 * <p>判据是"类名里含 {@code -waypoint} 标记段"：两个 Istio 常量名都命中，同时容忍派生命名
 * （如带后缀的变体）。<b>不用</b> {@code endsWith} —— 名字后面再挂东西（{@code istio-waypoint-1-20-0}）
 * 就漏判了。宽松方向是安全的：把非 waypoint 误判成 waypoint 只是让候选多一项（用户看得见），
 * 反过来会让候选空掉。
 *
 * <h2>类型投影：为什么候选必须带 {@code waypoint-for}（2026-10-09 起）</h2>
 * {@code istio.io/use-waypoint} 指向的 waypoint 必须能处理<b>该处流量最初的目标类型</b>，
 * 否则 istio <b>静默放行</b>（L7 策略不生效且无任何报错）：
 * <ul>
 *   <li>命名空间级（东西向、目标是服务）→ 只能选 {@code service} 或 {@code all}</li>
 *   <li>Pod 级（pod template 上，目标是 Pod/VM IP）→ 只能选 {@code workload} 或 {@code all}</li>
 * </ul>
 * 官方原文（Add workloads to the mesh · Label reference）："By default waypoints accept traffic for
 * services. For example, when you label a pod to use a specific waypoint via the
 * istio.io/use-waypoint label, the waypoint should be labeled istio.io/waypoint-for with the value
 * workload or all." —— 平台建 waypoint 时默认写的正是 {@code service}，所以"Pod 级标签 + 默认 waypoint"
 * 这个组合必然是死配置。故候选一律以「名字 + 类型」成对下发（{@link WaypointRefDTO}），
 * 由选择器按类型过滤并把不匹配的候选说明白。
 *
 * <h2>没有数量上限（2026-10-09 起，取代此前的「每 ns 至多一个」）</h2>
 * 早先平台限制"每命名空间至多一个 waypoint"，理由是 B3 的 {@code use-waypoint} 下拉只能表达 0/1。
 * 该前提已不成立：选择器现在按<b>名字</b>列候选（N 个也表达得出），而 istio 本身允许同 ns 多个
 * waypoint（按名字寻址、按"流量原始目标类型"分流以避免双重处理）。硬上限还会挡住三类合法用法：
 * 按工作负载划分安全边界的多个同类型 waypoint、waypoint 自身的版本灰度、控制面 revision 并存。
 * 于是改为：<b>不限制数量，"用哪个"由 label 取值决定，"能不能用"由类型过滤决定</b>。
 * 冗余（同 ns 已有能处理同类流量的 waypoint）只在 UI 提示，不阻断 —— 灰度场景下同类型两个是正当的。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayService {

    /**
     * waypoint 类别名里的标记段。两个 Istio 常量名（见类注释）都含它；用"含"而非"以…结尾"
     * 是为了容忍名字后面再挂后缀的派生命名。
     */
    private static final String WAYPOINT_CLASS_MARKER = "-waypoint";

    /** 决定该 waypoint 处理哪类流量的 label（打在 Gateway 上） */
    public static final String WAYPOINT_FOR_LABEL = "istio.io/waypoint-for";

    /** label 缺省时的取值。官方："This label is optional and the default value is service." */
    public static final String WAYPOINT_FOR_DEFAULT = "service";

    /** 能处理的东西向服务流量（命名空间级 use-waypoint 的可选集） */
    private static final List<String> SERVICE_CAPABLE = List.of("service", "all");
    /** 能处理 Pod/VM 直连流量（Pod 级 use-waypoint 的可选集） */
    private static final List<String> WORKLOAD_CAPABLE = List.of("workload", "all");

    private final K8sClient k8s;

    public List<GatewayDTO> list(GatewayDTO query) {
        return k8s.list(query);
    }

    public GatewayDTO get(String name, String tenantId, String clusterId, String namespace) {
        return k8s.get(dto(name, tenantId, clusterId, namespace));
    }

    public String yaml(String name, String tenantId, String clusterId, String namespace) {
        return k8s.yaml(dto(name, tenantId, clusterId, namespace));
    }

    public GatewayDTO create(GatewayDTO body) {
        return k8s.create(body);
    }

    public GatewayDTO update(GatewayDTO body) {
        return k8s.update(body);
    }

    public void delete(String name, String tenantId, String clusterId, String namespace) {
        k8s.delete(dto(name, tenantId, clusterId, namespace));
    }

    // ==================== waypoint 判定与类型投影 ====================

    /** 是否 waypoint 类别的 Gateway：只看 {@code spec.gatewayClassName}（见类注释的判定口径）。 */
    public static boolean isWaypointGateway(GatewayDTO gateway) {
        String className = gateway == null ? null : gateway.getGatewayClassName();
        if (className == null || className.isBlank()) {
            return false;
        }
        return className.trim().contains(WAYPOINT_CLASS_MARKER);
    }

    /**
     * 该 waypoint 处理哪类流量：读 label {@code istio.io/waypoint-for}，<b>缺省即 {@code service}</b>
     * （官方默认值），空串按缺省处理。取值以外的原样返回 —— 判定"能不能处理某类流量"由
     * {@link #canHandleService}/{@link #canHandleWorkload} 用白名单做，遇到不认识的值一律算"不能"。
     */
    public static String waypointForOf(GatewayDTO gateway) {
        String v = gateway == null || gateway.getLabels() == null
                ? null
                : gateway.getLabels().get(WAYPOINT_FOR_LABEL);
        if (v == null || v.isBlank()) {
            return WAYPOINT_FOR_DEFAULT;
        }
        return v.trim();
    }

    /** 能否承接东西向服务流量（命名空间级 {@code use-waypoint} 的目标）。{@code none} / 不认识的值 → false */
    public static boolean canHandleService(String waypointFor) {
        return waypointFor != null && SERVICE_CAPABLE.contains(waypointFor.trim());
    }

    /** 能否承接 Pod/VM 直连流量（Pod 级 {@code use-waypoint} 的目标）。{@code none} / 不认识的值 → false */
    public static boolean canHandleWorkload(String waypointFor) {
        return waypointFor != null && WORKLOAD_CAPABLE.contains(waypointFor.trim());
    }

    /**
     * 本命名空间的 waypoint 引用（按名字升序，名字+类型）。供工作负载的 ambient 开关（B3 §11）做候选与校验
     * —— 那条路径上的调用方是租户域，故走租户 client。
     * <p>平台侧的同一查询走 {@code /mesh/gateways}（平台管理员无租户上下文），两边都归口到
     * {@link #waypointRefsOf} 做投影 —— 判定口径只能有一处。
     */
    public List<WaypointRefDTO> waypointRefs(String tenantId, String clusterId, String namespace) {
        GatewayDTO q = new GatewayDTO();
        q.setTenantId(tenantId);
        q.setClusterId(clusterId);
        q.setNamespace(namespace);
        return waypointRefsOf(k8s.list(q));
    }

    /** Gateway 列表 → waypoint 引用（升序，剔除空名）。判定与投影的唯一实现，两侧数据源共用。 */
    public static List<WaypointRefDTO> waypointRefsOf(List<GatewayDTO> gateways) {
        if (gateways == null) {
            return List.of();
        }
        return gateways.stream()
                .filter(GatewayService::isWaypointGateway)
                .filter(g -> g.getName() != null && !g.getName().isBlank())
                .map(g -> {
                    WaypointRefDTO ref = new WaypointRefDTO();
                    ref.setName(g.getName());
                    ref.setWaypointFor(waypointForOf(g));
                    return ref;
                })
                .sorted(Comparator.comparing(WaypointRefDTO::getName))
                .toList();
    }

    /** 查询 DTO：apiPath 内置于 DTO */
    private GatewayDTO dto(String name, String tenantId, String clusterId, String namespace) {
        GatewayDTO d = new GatewayDTO();
        d.setName(name);
        d.setTenantId(tenantId);
        d.setClusterId(clusterId);
        d.setNamespace(namespace);
        return d;
    }

}
