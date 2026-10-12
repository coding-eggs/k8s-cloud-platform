package com.coding.k8sserver.controllers.cluster;

import com.coding.common.models.k8s.dto.NodeAddressDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.AccessBoundaryAware;
import com.coding.k8sserver.components.ResourceAccessResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 集群域 - 节点地址窄投影（{@code GET /nodes/addresses}）：只回节点名 + 内网/外网 IP + Ready。
 * 消费方是工作负载列表页的「点 nodePort 直开」——它只需要知道"哪台机器能连"。
 *
 * <h2>为什么独立成一个 controller 类（而不是往 {@link NodeController} 加方法）</h2>
 * {@code BoundaryAuthorizationManager} 读的是<b>handler 所属类</b>声明的 {@link AccessBoundary}。
 * {@code NodeController} 声明 {@code PLATFORM}（集群管理面），往它上面加方法，同一个类里的新方法
 * 一样要求调用方持有 {@code PLATFORM_SCOPE} —— 而本端点租户必须能到。故单开一个类声明 {@code UPSTREAM}
 * （{@code /nodes/addresses} 是字面路径，Spring 的 pattern 比较会优先于 {@code NodeController} 的
 * {@code /{name}} 模板，两族的 {@code @RequestMapping("/nodes")} 并存不会撞车）。
 *
 * <h2>边界：UPSTREAM —— 与 PersistentVolumeController 同类，是第二个这类例外</h2>
 * 本层不做边界判定，收窄在上游 platform-api（{@code POST /cluster/node-addresses} 挂
 * {@code tenant:cluster:capability:view}）。<b>已知并显式接受的残留</b>：持任意已认证 token 直连
 * k8s-server 的本端点即可读到该集群节点 IP（同 PV 那条的形态）。之所以接受：这份数据面就是
 * "节点 IP"，租户角色的默认权限里本就有（点 nodePort 要用）；且节点 IP 不随租户/命名空间变化，
 * 没有"越权看到别人对象"的语义。闭合它需要"只接受 platform-api 调用"的内部凭证，与 PV 那条一并留给下一步。
 *
 * <h2>为什么用 admin 凭据</h2>
 * 租户 SA 的 K8s RBAC 模板里没有 {@code nodes}（也不该为一个跳转链接开）。本端点在 k8s-core 侧
 * 只列一次节点并投影成 name+IP，不回容量/标签/污点/Pod 数（见 {@code NodeAddressDTO} 的类注释）。
 */
@Tag(name = "集群域-Node", description = "节点地址窄投影（NodePort 直连用，UPSTREAM 边界）")
@RestController
@RequestMapping("/nodes")
public class NodeAddressController implements AccessBoundaryAware {

    private final KubernetesOperationsFactory operationsFactory;
    private final ResourceAccessResolver accessResolver;

    public NodeAddressController(KubernetesOperationsFactory operationsFactory, ResourceAccessResolver accessResolver) {
        this.operationsFactory = operationsFactory;
        this.accessResolver = accessResolver;
    }

    /**上游收窄：platform-api 侧按权限码判"谁能拿节点地址"，本层只保证集群已登记 */
    @Override
    public AccessBoundary accessBoundary() {
        return AccessBoundary.UPSTREAM;
    }

    @GetMapping("/addresses")
    @Operation(summary = "节点地址窄投影",
            description = "全部节点的 name + InternalIP/ExternalIP + Ready；不含容量/标签/污点（NodePort 直连用）")
    public ResponseData<List<NodeAddressDTO>> addresses(@RequestParam String clusterId) {
        accessResolver.assertClusterAccess(clusterId);
        return new ResponseData<>(operationsFactory.getNodeOperation(clusterId).addresses());
    }
}
