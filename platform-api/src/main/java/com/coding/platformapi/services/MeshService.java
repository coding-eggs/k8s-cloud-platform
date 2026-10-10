package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.CapabilitySummaryDTO;
import com.coding.common.models.k8s.dto.GatewayClassDTO;
import com.coding.common.models.k8s.dto.MeshStatusDTO;
import com.coding.common.models.k8s.dto.WaypointRefDTO;
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
 * <h2>mesh-status 的两半，两个来源（2026-10-10 起）</h2>
 * <ul>
 *   <li><b>discovery</b>（{@code hasGatewayApi} / {@code gatewayApiVersions} / {@code hasIstio}）——
 *       读 {@code k8s_cluster.capability} 列，走 {@link ClusterCapabilityService}。判据只有这一处
 *       （以前 k8s-core 的 {@code MeshOperations} 里还有一份，已删）。</li>
 *   <li><b>资源 probe</b>（{@code istioAmbient}）—— 走 k8s-server 的活探测（查 ztunnel DaemonSet）。
 *       探测失败按 {@code false}，<b>不</b>上抛：它只是横幅上的一个信息位，不是门禁。
 *       这也是本层唯一吞异常的地方 —— discovery 那半读 DB，与集群当前是否连得上无关。</li>
 * </ul>
 * 为什么 ambient 必须上抛原样、而 discovery 不是：{@code hasGatewayApi=false} 才是硬门禁（前端整组 create
 * 禁用）。若把它也建立在"探测失败"之上，一次集群断开就会被显示成"未安装 Gateway API" —— 把"不知道"伪装成"没有"。
 * 现在这个风险消失了：capability 读的是 DB 列，集群断开也照常返回最后探测到的快照。
 * <p><b>对外 shape 不变</b>：{@code MeshStatusDTO} 仍是四个字段，前端零改动；变的只是这四个字段在
 * 后端由谁拼起来（以前 k8s-core 造，现在本层造）。
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
    /** capability 列的派生判据（唯一出处） */
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
     * 服务网格状态（istio / ambient / Gateway API + versions）—— 四个字段两个来源，见类注释。
     *
     * <p>组装口径（照抄 k8s-core {@code MeshOperations} 搬到 api 前的判定，逐条对齐）：
     * <ul>
     *   <li>{@code hasGatewayApi} = capability 含 {@code gateway.networking.k8s.io} —— 本模块硬门禁</li>
     *   <li>{@code gatewayApiVersions} = 该 group 的 versions；未探测 → 空列表</li>
     *   <li>{@code hasIstio} = capability 含 {@code istio.io} <b>或</b> {@code networking.istio.io}</li>
     *   <li>{@code istioAmbient} = k8s-server 活探测；<b>失败按 false，不上抛</b>（信息性降级）</li>
     * </ul>
     * 未探测（capability 空列）的语义是"未探测"而非"没装"：三个 flag 全 false，前端提示「未探测/未安装」
     * 并给「刷新能力」按钮，与 {@code useClusterCapability} 的口径一致。
     */
    public MeshStatusDTO meshStatus(String clusterId) {
        //两个 flag 一次取（summary 内部读一次 capability 列），versions 单取；判定逻辑全在 ClusterCapabilityService，
        //本层不自己 containsKey（"Gateway API 是哪个 group""Istio 在哪两个 group"只有那一处定义）
        CapabilitySummaryDTO cap = capabilityService.summary(clusterId);
        MeshStatusDTO dto = new MeshStatusDTO();
        dto.setHasGatewayApi(cap.isHasGatewayApi());
        dto.setHasIstio(cap.isHasIstio());
        dto.setGatewayApiVersions(
                capabilityService.versionsOf(clusterId, ClusterCapabilityService.GATEWAY_API_GROUP));
        dto.setIstioAmbient(probeAmbientQuietly(clusterId));
        return dto;
    }

    /** ambient 活探测：失败按 false 并告警 —— 它是信息位不是门禁（判据见类注释）。 */
    private boolean probeAmbientQuietly(String clusterId) {
        try {
            return k8s.meshAmbient(clusterId);
        } catch (Exception e) {
            log.warn("集群 {} ambient 活探测失败（按未安装 ambient 处理）：{}", clusterId, e.getMessage());
            return false;
        }
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
