package com.coding.k8sserver.controllers.gateway;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.GatewayDTO;
import com.coding.common.models.k8s.dto.ParentGatewayRefDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.AccessBoundaryAware;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.components.ResourceAccessResolver.AccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 路由父引用（{@code parentRefs}）的 Gateway 候选 —— **租户可调的窄投影**。
 *
 * <p><b>为什么需要它</b>：租户配 HTTPRoute 时要把路由挂到对外网关上，那个网关在平台代管的
 * {@code istio-system} 里。按"平台 ns 不分配给租户"的边界（spec §3），租户对它没有任何 K8s 权限，
 * 于是父资源下拉恒空、用户只能手打名字 —— 打错还**静默不挂载**（Gateway API 不报错）。
 *
 * <p>同形先例：{@code /mesh/gatewayclass-refs}、{@code /nodes/addresses}、
 * {@code /storageclasses/provisioners}。四条规范见 spec §5.1。
 *
 * <h2>两个命名空间参数，别搞混</h2>
 * <ul>
 *   <li>{@code namespace} —— **路由自己**所在的命名空间。它是**边界**：租户必须对它有一条分配行
 *       （平台管理员则走 admin 模式）。跨 ns 引用父网关时它**不变**。</li>
 *   <li>{@code targetNamespace} —— 父引用**指向**的命名空间（可空 = 与路由同一个）。它是**读取目标**，
 *       只有三种情况允许：与路由同级、在平台网关白名单里、或调用方本来就对它有权限。</li>
 * </ul>
 * ⚠️ 如果把两者合成一个参数，租户就能拿"自己有分配行的 ns"当通行证去读任意命名空间 ——
 * 那这个端点会变成越权入口，比不开还糟。
 */
@Tag(name = "租户域-路由父网关候选", description = "路由 parentRefs 的 Gateway 候选（自己的 ns + 平台网关白名单）")
@RestController
@RequestMapping("/parent-gateways")
@RequiredArgsConstructor
public class ParentGatewayRefController implements AccessBoundaryAware {

    /**
     * 平台代管、租户**可以引用**的父网关命名空间。
     *
     * <p>⚠️ 写死在服务端，**不接受调用方传** —— 这是本端点唯一比通用 {@code /gateways/list} 多出来的东西，
     * 一旦可由调用方指定，它就退化成"想读哪个 ns 就读哪个 ns"的越权入口。
     */
    static final List<String> PLATFORM_GATEWAY_NAMESPACES = List.of("istio-system");

    private static final String WAYPOINT_FOR_LABEL = "istio.io/waypoint-for";

    @Override
    public AccessBoundary accessBoundary() {
        return AccessBoundary.TENANT;
    }

    private final KubernetesOperationsFactory operationsFactory;
    private final ResourceAccessResolver accessResolver;

    /**
     * @param namespace       路由所在命名空间（边界）
     * @param targetNamespace 父引用指向的命名空间（可空 = 同 namespace）
     * @param tenantId        平台管理员代操作时可空（服务端按 (cluster, ns) 反查归属）；租户传了须与 token 一致
     */
    @PostMapping
    @Operation(summary = "路由父引用候选",
            description = "返回 targetNamespace（缺省 = 路由自己那个）里的 Gateway 窄投影。"
                    + "targetNamespace 只允许：与路由同命名空间 / 平台网关白名单 / 调用方本来有权访问的命名空间；"
                    + "其余情况返回空列表（候选列表不该报错）。")
    public ResponseData<List<ParentGatewayRefDTO>> list(@RequestParam(required = false) String tenantId,
                                                        @RequestParam String clusterId,
                                                        @RequestParam String namespace,
                                                        @RequestParam(required = false) String targetNamespace) {
        // ① 边界：路由自己的命名空间 —— 租户必须有分配行（平台管理员走 admin 模式）
        AccessContext ctx = accessResolver.resolveNamespacedAccess(tenantId, clusterId, namespace);

        // ② 读取目标：不是"路由的 ns"、也不在白名单里时，必须本来就对它有权
        String target = StringUtils.hasText(targetNamespace) ? targetNamespace : namespace;
        if (!mayRead(ctx, clusterId, namespace, target)) {
            return new ResponseData<>(List.of());
        }

        NamespacedOperations<GatewayDTO> ops =
                operationsFactory.getAdminNamespacedOperation(ResourceType.GATEWAY, clusterId);
        return new ResponseData<>(ops.list(target, null, null).stream()
                .map(g -> toRef(g, target))
                .toList());
    }

    /**
     * 能不能读这个命名空间的 Gateway。
     *
     * <p>三种放行：与路由同级（已在 ① 判过）/ 平台网关白名单 / 平台管理员 / 租户对它本来就有分配行。
     * 最后一条走的是**同一个** {@code hasNamespaceAccess}（经 {@link ResourceAccessResolver#mayAccessNamespace}）
     * —— 不在这里另写一份。
     */
    boolean mayRead(AccessContext ctx, String clusterId, String routeNamespace, String target) {
        if (target.equals(routeNamespace) || PLATFORM_GATEWAY_NAMESPACES.contains(target)) {
            return true;
        }
        if (ctx.adminMode()) {
            return true;
        }
        return ctx.tenantId() != null
                && accessResolver.mayAccessNamespace(ctx.tenantId(), clusterId, target);
    }

    /** 宽 DTO → 窄投影。只留编辑那一个下拉要用的字段。 */
    static ParentGatewayRefDTO toRef(GatewayDTO g, String namespace) {
        ParentGatewayRefDTO ref = new ParentGatewayRefDTO();
        ref.setName(g.getName());
        ref.setNamespace(namespace);
        ref.setWaypointFor(g.getLabels() == null ? null : g.getLabels().get(WAYPOINT_FOR_LABEL));

        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (g.getListeners() != null) {
            for (GatewayDTO.Listener l : g.getListeners()) {
                String n = l.getName() == null ? "" : l.getName().trim();
                if (!n.isEmpty()) {
                    names.add(n);
                }
            }
        }
        ref.setListenerNames(List.copyOf(names));
        return ref;
    }
}
