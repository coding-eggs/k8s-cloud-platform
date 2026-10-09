package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.GatewayClassDTO;
import com.coding.common.models.k8s.dto.MeshStatusDTO;
import com.coding.platformapi.k8s.K8sMeshClient;
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
 * <h2>mesh-status 为什么不在本层吞异常</h2>
 * 探测失败有三种成因（集群断开 / 未刷新能力 / admin RBAC 未覆盖），本层无法区分，吞掉后一律
 * 变成 {@code hasGatewayApi=false} —— 横幅会显示成"未安装 Gateway API"，把"不知道"伪装成"没有"。
 * 故异常照常上抛，由调用方（前端）退化为中性的「未探测」态 —— 与 {@code useClusterCapability}
 * 对 {@code /cluster/capability/get} 的处理口径一致（catch → cap={} → 未探测）。
 * <p><b>有意不加 TTL 缓存</b>（计划里列了"短 TTL 缓存"）：本端点的成本是 1 次 DB 读 + 1–2 次
 * 已被 client 缓存的下游 get；而相邻的「刷新能力」动作（{@code /cluster/capability/refresh}）
 * 用户期望立即在横幅生效，加 TTL 会造出一个"刚刷新完但横幅还是旧的"窗口 —— 省下的开销换不来这个
 * 困惑。真需要缓存时，正确做法是只缓存 ztunnel 活探测那一段（唯一不来自 DB 的部分）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MeshService {

    private final K8sMeshClient k8s;

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

    /** 服务网格状态（istio / ambient / Gateway API + versions）。失败上抛，由调用方退化为「未探测」。 */
    public MeshStatusDTO meshStatus(String clusterId) {
        return k8s.meshStatus(clusterId);
    }

    // ==================== waypoint 候选（平台侧读，供命名空间编辑器） ====================

    /**
     * 某命名空间内的 waypoint Gateway 名列表（升序），供「命名空间编辑」的 {@code istio.io/use-waypoint}
     * 下拉。平台限制每命名空间至多一个 waypoint（{@link GatewayService} 的创建守卫），故常态是 0 或 1 个。
     *
     * <p>两点刻意的取舍：
     * <ul>
     *   <li><b>走平台侧端点</b>：命名空间是平台侧资源，而 Gateway 是租户域资源 —— 平台管理员没有租户
     *       上下文，走不了租户的 {@code /gateways/list}。故经 {@code /mesh/gateways}（PLATFORM 边界、
     *       admin client）读。</li>
     *   <li><b>判定留在本层</b>：k8s-server 只回该命名空间的全部 Gateway，"是不是 waypoint" 用
     *       {@link GatewayService#isWaypointGateway} —— 判定口径只能有一处，否则两跳迟早漂移。</li>
     * </ul>
     */
    public List<String> waypointCandidates(String clusterId, String namespace) {
        return GatewayService.waypointNamesOf(k8s.namespaceGateways(clusterId, namespace));
    }

}
