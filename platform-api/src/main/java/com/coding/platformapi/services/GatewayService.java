package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.GatewayDTO;
import com.coding.platformapi.k8s.K8sClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Gateway 业务层：六操作透传 + <b>waypoint per-ns 唯一性校验</b>（平台附加规则）。
 *
 * <h2>waypoint per-ns 唯一性（Phase 3）</h2>
 * 这是<b>平台加在 gateway-api 之上的规则</b>，CRD 层面不存在 —— Istio 允许一个命名空间里放多个
 * waypoint（官方文档甚至演示了跨命名空间的 waypoint 网关），但平台的产品约束是"每命名空间至多一个"：
 * B3 的「使用 waypoint」下拉要给出 0/1 选择，而资源侧的挂载方式是
 * {@code istio.io/use-waypoint: <gateway-name>} <b>按名字指向</b>一个 waypoint ——
 * 同命名空间存在两个时那个选择器就表达不出"用哪个"。
 *
 * <h3>判定口径：看 {@code spec.gatewayClassName}</h3>
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
 * 就漏判了，而漏判会让"每 ns 一个"静默失效。
 * <p>这条宽松是有意的、且方向安全：把非 waypoint 误判成 waypoint 只会<b>多挡一次</b>创建（用户看得见、
 * 可改名绕开），反过来则让约束悄悄不生效。
 *
 * <h3>判定失败时的取向：保守拒绝</h3>
 * 校验要 list 同命名空间的 Gateway；这次 list 失败（集群断开、RBAC 未覆盖）时无法确认是否已有
 * waypoint，<b>拒绝创建</b>并明说原因 —— 同 {@code CalicoService.deleteIppool} 删除守卫的口径
 * （"无法确认占用则保守拒绝"）。不为省一次失败而放行一个可能破坏不变式的写入。
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
        assertWaypointUniqueInNamespace(body, null);
        return k8s.create(body);
    }

    public GatewayDTO update(GatewayDTO body) {
        // 编辑也要判：把一个普通 Gateway 改成 waypoint 类别同样会占掉该命名空间的唯一名额；
        // selfName 传自己，避免"编辑自己"被自己挡住
        assertWaypointUniqueInNamespace(body, body.getName());
        return k8s.update(body);
    }

    public void delete(String name, String tenantId, String clusterId, String namespace) {
        k8s.delete(dto(name, tenantId, clusterId, namespace));
    }

    // ==================== waypoint per-ns 唯一性 ====================

    /**
     * 同一 (clusterId, namespace) 下至多一个 waypoint Gateway。
     *
     * @param selfName 更新场景下传自身名（自我排除）；创建场景传 null
     * @throws CloudPlatformException 已存在另一个 waypoint，或无法确认（list 失败）
     */
    private void assertWaypointUniqueInNamespace(GatewayDTO body, String selfName) {
        if (!isWaypointGateway(body)) {
            return; // 普通 Gateway 不占名额：不查列表、不引入额外开销
        }
        List<GatewayDTO> siblings;
        try {
            siblings = k8s.list(listQuery(body));
        } catch (Exception e) {
            // 保守拒绝：无法确认就不能放行（同 CalicoService 删除守卫）
            throw new CloudPlatformException(EnumResponseType.ERROR,
                    "无法确认命名空间「" + body.getNamespace() + "」是否已有 waypoint Gateway，已拒绝本次操作：" + e.getMessage());
        }
        for (GatewayDTO other : siblings) {
            if (other.getName() == null || other.getName().equals(selfName)) {
                continue; // 空名防御 + 编辑自己不算冲突
            }
            if (isWaypointGateway(other)) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "命名空间「" + body.getNamespace() + "」已存在 waypoint Gateway「" + other.getName()
                                + "」。每个命名空间至多一个 waypoint（挂载方 istio.io/use-waypoint 按名字指向它，"
                                + "多个会使该选择失去意义）；请先删除或改用那个 Gateway。");
            }
        }
    }

    /** 是否 waypoint 类别的 Gateway：只看 {@code spec.gatewayClassName}（见类注释的判定口径）。 */
    public static boolean isWaypointGateway(GatewayDTO gateway) {
        String className = gateway == null ? null : gateway.getGatewayClassName();
        if (className == null || className.isBlank()) {
            return false;
        }
        return className.trim().contains(WAYPOINT_CLASS_MARKER);
    }

    /**
     * 本命名空间的 waypoint Gateway 名（升序）。供工作负载的 ambient 开关（B3 §11）做候选与存在性校验 ——
     * 那条路径上的调用方是租户域，故走租户 client。
     * <p>平台侧的同一查询走 {@code /mesh/gateways}（平台管理员无租户上下文），两边都归口到
     * {@link #waypointNamesOf} 做筛选 —— 判定口径只能有一处。
     */
    public List<String> waypointNames(String tenantId, String clusterId, String namespace) {
        GatewayDTO q = new GatewayDTO();
        q.setTenantId(tenantId);
        q.setClusterId(clusterId);
        q.setNamespace(namespace);
        return waypointNamesOf(k8s.list(q));
    }

    /** Gateway 列表 → waypoint 名（升序，剔除空名）。判定与投影的唯一实现，两侧数据源共用。 */
    public static List<String> waypointNamesOf(List<GatewayDTO> gateways) {
        if (gateways == null) {
            return List.of();
        }
        return gateways.stream()
                .filter(GatewayService::isWaypointGateway)
                .map(GatewayDTO::getName)
                .filter(name -> name != null && !name.isBlank())
                .sorted()
                .toList();
    }

    /** list 查询 DTO：只带边界三元组，不把表单里其余字段当成查询条件发出去 */
    private GatewayDTO listQuery(GatewayDTO body) {
        GatewayDTO q = new GatewayDTO();
        q.setTenantId(body.getTenantId());
        q.setClusterId(body.getClusterId());
        q.setNamespace(body.getNamespace());
        return q;
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
