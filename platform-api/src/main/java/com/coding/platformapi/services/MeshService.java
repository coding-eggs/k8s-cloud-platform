package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.GatewayClassDTO;
import com.coding.common.models.k8s.dto.MeshStatusDTO;
import com.coding.common.models.k8s.dto.WaypointRefDTO;
import com.coding.platformapi.k8s.K8sMeshClient;
import com.coding.platformapi.models.ComponentStatusDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 服务网格业务层：GatewayClass CRUD（平台管理面）+ 租户只读引用投影 + 网格状态探测。
 * <p>分层约定见 docs/development/backend-layering.md。
 *
 * <h2>两条通路，两种授权面</h2>
 * <ul>
 *   <li>{@code list/get/yaml/create/update/delete} —— 平台管理面（权限码 {@code platform:cluster:manage}）。
 *       GatewayClass 是集群级基础设施（controllerName 决定谁接管流量），按租户域开放没有意义。</li>
 *   <li>{@link #gatewayClassRefs} —— <b>窄投影只读</b>，任何登录用户可读。租户在 Gateway 编辑器里要选
 *       {@code gatewayClassName}，需要的是"有哪些 class、各自谁实现"，<b>不是</b>整张 GatewayClass 表。
 *       按 frontend-permission-conventions.md §5：页面需要什么数据就开一个与那份数据同粒度的端点，
 *       不要"先借一个权限更高的端点再用前端门控兜住"。</li>
 * </ul>
 *
 * <h2>mesh-status 的来源（S1.2 收口后）</h2>
 * 三个布尔（{@code hasIstio} / {@code hasGatewayApi} / {@code istioAmbient}）全部从
 * {@link ClusterComponentService} 的组件清单派生（组件 {@code istio} / {@code gateway-api}，
 * <b>要求 {@code INSTALLED_HEALTHY}</b>）；{@code gatewayApiVersions} 仍来自
 * {@code k8s_cluster.capability} 列（组件清单不回答"哪些版本"）。
 * <p>{@code istioAmbient} 跟着 {@code istio} 一起判定：ambient 数据面（cni/ztunnel）与 istiod 在同一个
 * 安装单元里（2026-10-18 合并为单个组件，不再单列 {@code istio-ambient}）。手工按 sidecar 模式装的
 * Istio 会显示为 ambient —— 平台自己装的永远是 ambient，这是有意的简化。
 * <p>收口前这三条有两个来源（capability 列 + ztunnel 活探），与 CI / 网络 / HPA 四处各判一次。
 * 现在派生实现只有 {@link ClusterComponentService} 一份，本层只做投影。
 * <p><b>对外 shape 不变</b>：{@code MeshStatusDTO} 仍是四个字段，前端零改动；变的只是这四个字段在
 * 后端由谁拼起来。
 * <p>未探测（组件全 UNKNOWN）→ 三个布尔全 false，与既有「未探测」的渲染一致 —— DTO 只有布尔，
 * 这里不给"不知道"留位置，但也不把它说成"没装"（前端按全 false 显示未探测/未安装）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MeshService {

    private final K8sMeshClient k8s;
    /** 组件状态的唯一派生实现（S1.2 起三个布尔都读它） */
    private final ClusterComponentService componentService;
    /** capability 列的派生判据（只用于 gatewayApiVersions） */
    private final ClusterCapabilityService capabilityService;

    // ==================== GatewayClass CRUD（平台管理面） ====================

    public List<GatewayClassDTO> listGatewayClasses(GatewayClassDTO query) {
        return k8s.listGatewayClasses(query);
    }

    public GatewayClassDTO getGatewayClass(String clusterId, String name) {
        return k8s.getGatewayClass(clusterId, name);
    }

    public String gatewayClassYaml(String clusterId, String name) {
        return k8s.gatewayClassYaml(clusterId, name);
    }

    public GatewayClassDTO createGatewayClass(GatewayClassDTO body) {
        return k8s.createGatewayClass(body);
    }

    public GatewayClassDTO updateGatewayClass(GatewayClassDTO body) {
        return k8s.updateGatewayClass(body);
    }

    public void deleteGatewayClass(String clusterId, String name) {
        k8s.deleteGatewayClass(clusterId, name);
    }

    // ==================== 租户只读引用（窄投影） ====================

    /**
     * GatewayClass 引用候选：只回 {@code name} / {@code controllerName} / {@code description} 三个字段。
     * <p>供 Gateway 编辑器选 {@code gatewayClassName}。剥掉 {@code parametersRef}、{@code conditions}
     * 与 metadata —— 它们对"选哪个 class"无用，却是唯一可能带实现私有配置引用的部分。
     */
    public List<GatewayClassDTO> gatewayClassRefs(String clusterId) {
        GatewayClassDTO query = new GatewayClassDTO();
        query.setClusterId(clusterId);
        return k8s.listGatewayClasses(query).stream().map(MeshService::toRef).toList();
    }

    private static GatewayClassDTO toRef(GatewayClassDTO src) {
        GatewayClassDTO ref = new GatewayClassDTO();
        ref.setName(src.getName());
        ref.setControllerName(src.getControllerName());
        ref.setDescription(src.getDescription());
        ref.setCreationTime(src.getCreationTime());
        return ref;
    }

    // ==================== 网格状态 ====================

    /**
     * 服务网格状态（istio / ambient / Gateway API + versions）—— 三布尔从组件清单派生，见类注释。
     *
     * <p>⚠️ 两个刻意的语义（评审 2026-10-17 修正）：
     * <ul>
     *   <li><b>「装了但不健康」仍算装了</b> —— 横幅问的是数据面形态（ambient / sidecar），
     *       可用性由 flags（{@code ambient-mesh} / {@code service-mesh}）回答。把 UNHEALTHY 判成
     *       "没装"会让 istiod 重启时横幅写「Istio 未安装」。</li>
     *   <li><b>「不知道」（UNKNOWN）退回 capability 快照</b> —— 快照读 DB，集群断开也照常可用。
     *       不退回就会在集群不可达时说「Istio 未安装」—— 正是本仓反复修过的那个坑。</li>
     * </ul>
     * <p>{@code istioAmbient} 没有快照可退（capability 列不回答 ztunnel），UNKNOWN 按 false ——
     * 与改造前的活探测失败同一行为；它跟随 {@code istio} 组件判定。
     * <p>异常不会从这里逃出去：{@link ClusterComponentService#status} 把探测失败
     * 转成 {@code UNKNOWN}，不抛。
     */
    public MeshStatusDTO meshStatus(String clusterId) {
        List<ComponentStatusDTO> components = componentService.status(clusterId);
        List<String> gatewayVersions =
                capabilityService.versionsOf(clusterId, ClusterCapabilityService.GATEWAY_API_GROUP);

        MeshStatusDTO dto = new MeshStatusDTO();
        dto.setHasGatewayApi(installed(components, "gateway-api")
                || (unknown(components, "gateway-api") && !gatewayVersions.isEmpty()));
        dto.setHasIstio(installed(components, "istio")
                || (unknown(components, "istio") && capabilityService.hasIstio(clusterId)));
        dto.setGatewayApiVersions(gatewayVersions);
        dto.setIstioAmbient(installed(components, "istio"));
        return dto;
    }

    /** 装了（健康与否都算）—— 与"可用"是两件事 */
    private static boolean installed(List<ComponentStatusDTO> components, String code) {
        return components.stream().anyMatch(s -> code.equals(s.getCode())
                && (ClusterComponentService.HEALTHY.equals(s.getState())
                    || ClusterComponentService.UNHEALTHY.equals(s.getState())));
    }

    /** 没有任何答案（探测失败 / 未探测 / 清单里没这一条） */
    private static boolean unknown(List<ComponentStatusDTO> components, String code) {
        return components.stream().noneMatch(s -> code.equals(s.getCode())
                && (ClusterComponentService.HEALTHY.equals(s.getState())
                    || ClusterComponentService.UNHEALTHY.equals(s.getState())
                    || ClusterComponentService.NOT_INSTALLED.equals(s.getState())));
    }

    // ==================== waypoint 候选（平台侧读，供命名空间编辑器） ====================

    /**
     * 某命名空间内的 waypoint 引用（名字 + 处理哪类流量，升序），供「命名空间编辑」的
     * {@code istio.io/use-waypoint} 下拉。数量不设上限（2026-10-09 起取消「每 ns 至多一个」）。
     *
     * <p>三点刻意的取舍：
     * <ul>
     *   <li><b>走平台侧端点</b>：命名空间是平台侧资源，而 Gateway 是租户域资源 —— 平台管理员没有租户
     *       上下文，走不了租户的 {@code /gateways/list}。故经 {@code /mesh/gateways}（PLATFORM 边界、
     *       admin client）读。</li>
     *   <li><b>判定与投影留在本层</b>：k8s-server 只回该命名空间的全部 Gateway，"是不是 waypoint""处理
     *       哪类流量"都用 {@link GatewayService#waypointRefsOf} —— 判定口径只能有一处，否则两跳迟早漂移。</li>
     *   <li><b>必须带类型</b>：命名空间级 {@code use-waypoint} 只能指向能处理服务流量的 waypoint
     *       （{@code service} / {@code all}），否则 istio 静默放行、L7 策略不生效。选择器要按类型过滤，
     *       所以这里回的不只是名字（见 {@link WaypointRefDTO}）。</li>
     * </ul>
     */
    public List<WaypointRefDTO> waypointCandidates(String clusterId, String namespace) {
        return GatewayService.waypointRefsOf(k8s.namespaceGateways(clusterId, namespace));
    }

}
