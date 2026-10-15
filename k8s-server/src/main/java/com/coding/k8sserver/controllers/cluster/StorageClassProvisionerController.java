package com.coding.k8sserver.controllers.cluster;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.StorageClassDTO;
import com.coding.common.models.k8s.dto.StorageClassProvisionerDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.ClusterOperations;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.AccessBoundaryAware;
import com.coding.k8sserver.components.ResourceAccessResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 集群域 - StorageClass 窄投影（{@code POST /storageclasses/provisioners}）：只回 name + provisioner。
 * 消费方是 CI 的能力探针 —— 它只需要判断"这个集群有没有一块能动态供应的盘"。
 *
 * <h2>为什么独立成一个 controller 类（而不是往 {@link StorageClassController} 加方法）</h2>
 * {@code BoundaryAuthorizationManager} 读的是<b>handler 所属类</b>声明的 {@link AccessBoundary}。
 * {@code StorageClassController} 声明 {@code PLATFORM}（集群管理面，见 V2026_10_07_3），
 * 往它上面加方法，同一个类里的新方法一样要求调用方持有 {@code PLATFORM_SCOPE} ——
 * 而本端点**租户必须能到**：流水线页面就是租户在用的（否则门控对他们永远答"没有可动态供应的
 * StorageClass"，而那正是 2026-10-15 修掉的那个 bug）。故单开一个类声明 {@code UPSTREAM}，
 * 与 {@link NodeAddressController} 完全同形；用 POST 而不是 GET，是为了不落在
 * {@code AbstractClusterResourceController} 的 {@code GET /{name}} 模板上（那条是 PLATFORM 边界）。
 *
 * <h2>边界：UPSTREAM —— 与 PersistentVolume / NodeAddress 同类，是第三个这类例外</h2>
 * 本层不做边界判定，收窄在上游 platform-api（{@code POST /pipeline/capability} 挂
 * {@code tenant:pipeline:read}）。<b>已知并显式接受的残留</b>：持任意已认证 token 直连 k8s-server
 * 的本端点即可读到该集群的 StorageClass 名与 provisioner（同 PV / 节点 IP 两条的形态）。
 * 之所以接受：①这份数据面只有两个字段，{@code parameters}（可能有 NFS 地址、密钥名）**不回**；
 * ②它不随租户/命名空间变化，没有"越权看到别人对象"的语义。闭合它需要"只接受 platform-api 调用"
 * 的内部凭证，与那两条一并留给下一步。
 *
 * <h2>为什么用 admin 凭据</h2>
 * 租户 SA 的 K8s RBAC 模板里没有 {@code storageclasses}（也不该为一次探测开）。
 */
@Tag(name = "集群域-StorageClass", description = "StorageClass 窄投影（CI 能力探针用，UPSTREAM 边界）")
@RestController
@RequestMapping("/storageclasses")
public class StorageClassProvisionerController implements AccessBoundaryAware {

    private final KubernetesOperationsFactory operationsFactory;
    private final ResourceAccessResolver accessResolver;

    public StorageClassProvisionerController(KubernetesOperationsFactory operationsFactory,
                                             ResourceAccessResolver accessResolver) {
        this.operationsFactory = operationsFactory;
        this.accessResolver = accessResolver;
    }

    /**上游收窄：platform-api 侧按权限码判"谁能问 CI 能不能跑"，本层只保证集群已登记 */
    @Override
    public AccessBoundary accessBoundary() {
        return AccessBoundary.UPSTREAM;
    }

    @PostMapping("/provisioners")
    @Operation(summary = "StorageClass 窄投影",
            description = "只回 name + provisioner（不回 parameters）；CI 能力探针判断有没有可动态供应的盘")
    public ResponseData<List<StorageClassProvisionerDTO>> provisioners(@RequestBody StorageClassDTO body) {
        accessResolver.assertClusterAccess(body.getClusterId());
        ClusterOperations<StorageClassDTO> ops =
                operationsFactory.getClusterOperation(ResourceType.STORAGE_CLASS, body.getClusterId());
        return new ResponseData<>(ops.list(null, null).stream()
                .map(sc -> {
                    StorageClassProvisionerDTO view = new StorageClassProvisionerDTO();
                    view.setName(sc.getName());
                    view.setProvisioner(sc.getProvisioner());
                    return view;
                })
                .toList());
    }
}
