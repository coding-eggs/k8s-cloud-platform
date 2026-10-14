package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.StorageClassDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * CI 能不能用 —— **整块可用性的唯一判据**（spec §9「capability 门控」）。
 *
 * <p>两个前提缺一不可：
 * <ol>
 *   <li><b>集群装了 Tekton</b> —— 没有 {@code tekton.dev} 就没有 {@code PipelineRun} 这个资源类型。
 *       判据来自 {@link ClusterCapabilityService} 的 capability 快照（**不打集群**）。</li>
 *   <li><b>有可动态供应的 StorageClass</b> —— 隐式共享 workspace 用 {@code volumeClaimTemplate}，
 *       它要现开一块 PVC（spec §3.3）。全是 {@code kubernetes.io/no-provisioner} 的静态盘
 *       会让每次运行都卡在绑定等待上。</li>
 * </ol>
 *
 * <p>⚠️ 门控的意义是**整块不可用而不是报 500**：前端拿这个结果把菜单与页面收起来
 * （部署后待验清单第一条：集群没装 Tekton 时 CI 菜单与页面整块消失）。
 * 所以它回的是「能不能用 + 为什么不能」，不是一个布尔。
 */
@Slf4j
@Service
public class PipelineCapabilityService {

    /** Local PV 这类不能动态供应的 provisioner 的固定名字 */
    private static final String NO_PROVISIONER = "kubernetes.io/no-provisioner";

    private final ClusterCapabilityService clusterCapability;
    private final com.coding.platformapi.k8s.K8sClient k8s;

    public PipelineCapabilityService(ClusterCapabilityService clusterCapability,
                                     com.coding.platformapi.k8s.K8sClient k8s) {
        this.clusterCapability = clusterCapability;
        this.k8s = k8s;
    }

    public Capability available(String clusterId) {
        if (!clusterCapability.hasTekton(clusterId)) {
            return new Capability(false, "该集群未安装 Tekton（没有 tekton.dev 资源）");
        }
        if (!hasDynamicStorageClass(clusterId)) {
            return new Capability(false,
                    "该集群没有可动态供应的 StorageClass —— 共享 workspace 需要现开一块 PVC");
        }
        return new Capability(true, null);
    }

    /**
     * 有没有一块**能动态供应**的 StorageClass。
     *
     * <p>只看 provisioner 是不是空的 / 是不是 {@code no-provisioner} —— 不去猜"默认 SC"是哪块：
     * 平台没给用户选 SC 的机会（spec 有意不做），Tekton 自己会用集群的默认块。
     * 所以这里问的是「集群到底有没有这个能力」，不是「哪一块」。
     */
    private boolean hasDynamicStorageClass(String clusterId) {
        StorageClassDTO query = new StorageClassDTO();
        query.setClusterId(clusterId);
        try {
            return k8s.listAll(query).stream().anyMatch(sc -> {
                String p = sc.getProvisioner();
                return p != null && !p.isBlank() && !NO_PROVISIONER.equals(p);
            });
        } catch (Exception e) {
            // 探测失败 → 当作不可用（fail-closed）：CI 跑起来卡在绑定等待上比"暂时不可用"更糟
            log.warn("探测集群 {} 的 StorageClass 失败，按不可用处理", clusterId, e);
            return false;
        }
    }

    /** @param reason 不可用时**必须**给原因 —— 不然用户只看到"功能没了" */
    public record Capability(boolean available, String reason) {
    }
}
