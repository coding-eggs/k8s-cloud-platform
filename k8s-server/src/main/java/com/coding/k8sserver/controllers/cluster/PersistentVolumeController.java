package com.coding.k8sserver.controllers.cluster;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.PersistentVolumeDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.controllers.base.AbstractClusterResourceController;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 集群域 - PersistentVolume：<b>本层不做边界判定的唯一例外</b>（{@link AccessBoundary#UPSTREAM}）。
 *
 * <h2>为什么是 UPSTREAM 而不是 PLATFORM</h2>
 * PV 是<b>集群级</b>对象（本层没有 namespace 可校验），但<b>租户必须能看自己的</b>——
 * platform-api 侧它的权限码是 {@code tenant:persistentvolume:*}、页面码是
 * {@code tenant:page:persistentvolume.list}。若声明 {@code PLATFORM}，租户侧「持久卷」页会被本层 403 打死。
 * <p>
 * 于是跨租户收窄的责任在上游：platform-api 的 {@code PersistentVolumeService} 按
 * {@code spec.claimRef.namespace ∈ 本租户在该集群的已分配命名空间} 过滤，未绑定 PV 对租户不可见。
 * <p>
 * <b>残留风险（已知并接受）</b>：持租户 token 直连 k8s-server 仍可读到全集群 PV（含他人 claimRef）。
 * 本层没有可用的判据，闭合它需要"只接受 platform-api 调用"的内部凭证 —— 不在本批。
 * 这条例外用 {@code UPSTREAM} 显式命名，就是为了让它可被一眼搜出，而不是靠路径隐式豁免。
 */
@Tag(name = "集群域-PersistentVolume", description = "集群级持久卷（边界=集群注册表；租户收窄在上游服务层）")
@RestController
@RequestMapping("/persistentvolumes")
public class PersistentVolumeController extends AbstractClusterResourceController<PersistentVolumeDTO> {

    public PersistentVolumeController(KubernetesOperationsFactory operationsFactory, ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.PERSISTENT_VOLUME;
    }

    @Override
    public AccessBoundary accessBoundary() {
        return AccessBoundary.UPSTREAM;
    }

}
