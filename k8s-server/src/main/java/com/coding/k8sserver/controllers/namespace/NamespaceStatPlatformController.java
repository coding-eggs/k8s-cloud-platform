package com.coding.k8sserver.controllers.namespace;

import com.coding.common.models.k8s.dto.NamespaceSnapshotDTO;
import com.coding.common.models.k8s.dto.admin.AdminNamespaceKeyRequest;
import com.coding.common.models.system.ResponseData;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.AccessBoundaryAware;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.components.ResourceAccessResolver.AccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 命名空间域 - 平台侧单命名空间行（`POST /namespacestats/platform/get`），边界 {@link AccessBoundary#PLATFORM}。
 *
 * <p><b>为什么另起一个类，而不是给 {@link NamespaceStatController} 加方法</b>：边界是**按 handler 所属类**读的
 * （{@link AccessBoundaryAware#boundaryOf} 取 {@code getBean()}，见 {@code BoundaryAuthorizationManager}）。
 * 既有那个类声明 {@code TENANT}，同类的任何方法都会落进同一判据 —— 平台入口必须放在声明 {@code PLATFORM} 的类里。
 * 两条路由共享 {@code /namespacestats} 前缀但不冲突：字面 {@code /platform/get}（本类，POST）与模板 {@code /{namespace}}
 * （{@link NamespaceStatController}，GET）分属不同 HTTP 方法，Spring 各自可达（{@code NamespaceStatRouteTest} 钉住）。
 *
 * <p><b>为什么需要它</b>：命名空间的行**不含租户维度**（{@code kube-system}/{@code default} 这类未分配命名空间
 * 根本没有租户），而平台侧页面（集群概览明细、命名空间详情页）恰恰要读它们的行 —— 走 {@code TENANT} 边界
 * 的 {@code resolveNamespacedAccess} 会因"无 tenantInfo 且 explicitTenantId 为空"直接拒（分配表三元组也根本不成立）。
 * 本类走 {@link ResourceAccessResolver#resolvePlatformNamespacedAccess(String)}：只校验集群已登记、不查分配表，
 * 身份由平台侧校验守门，client 取 admin。
 *
 * <p><b>只是采样</b>：不碰缓存、不设阈值（缓存键与新鲜度规则在 platform-api 的 {@code NamespaceStatService}，
 * 位置不变）。两个入口（本端点与 {@link NamespaceStatController}）写的是**同一个** {@code ns:<clusterId>:<namespace>} 键，
 * 所以"一个事实一个键"不受影响。
 */
@Tag(name = "平台管理域-命名空间统计", description = "单命名空间快照（平台侧：集群概览明细 / 命名空间详情页），边界=集群可达性 + 平台侧身份")
@RestController
@RequestMapping("/namespacestats")
public class NamespaceStatPlatformController implements AccessBoundaryAware {

    private final KubernetesOperationsFactory operationsFactory;
    private final ResourceAccessResolver accessResolver;

    public NamespaceStatPlatformController(KubernetesOperationsFactory operationsFactory,
                                           ResourceAccessResolver accessResolver) {
        this.operationsFactory = operationsFactory;
        this.accessResolver = accessResolver;
    }

    @Override
    public AccessBoundary accessBoundary() {
        return AccessBoundary.PLATFORM;
    }

    @PostMapping("/platform/get")
    @Operation(summary = "单命名空间快照（平台侧：不校验分配表，admin client）")
    public ResponseData<NamespaceSnapshotDTO> platformGet(@RequestBody AdminNamespaceKeyRequest req) {
        //平台侧身份校验 + 集群可达性校验都在这里；adminMode=true ⇒ 取 admin client（平台页面代平台看无租户命名空间）
        AccessContext ctx = accessResolver.resolvePlatformNamespacedAccess(req.getClusterId());
        return new ResponseData<>(operationsFactory
                .getNamespaceStatOperation(req.getClusterId(), ctx.tenantId(), ctx.adminMode())
                .row(req.getNamespace()));
    }
}
