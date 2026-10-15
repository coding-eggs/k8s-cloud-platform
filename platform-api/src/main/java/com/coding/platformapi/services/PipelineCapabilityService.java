package com.coding.platformapi.services;

import com.coding.platformapi.k8s.K8sPipelineClient;
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
 * <p>⚠️ 门控的意义是**整块不可用而不是报 500**：前端拿这个结果把「运行」按下去并说明原因
 * （部署后待验清单第一条）。所以它回的是「能不能用 + 为什么不能」，不是一个布尔。
 *
 * <p>⚠️ 两个前提的**数据来源不同**：Tekton 那个读平台侧的 capability 快照（不打集群）；
 * StorageClass 那个是**活探测**，走 k8s-server 的窄投影端点 —— 通用端点的边界是平台管理员专属，
 * 而流水线是租户在用的（原因见 {@link #hasDynamicStorageClass}）。
 */
@Slf4j
@Service
public class PipelineCapabilityService {

    /** Local PV 这类不能动态供应的 provisioner 的固定名字 */
    private static final String NO_PROVISIONER = "kubernetes.io/no-provisioner";

    private final ClusterCapabilityService clusterCapability;
    private final K8sPipelineClient k8s;

    public PipelineCapabilityService(ClusterCapabilityService clusterCapability, K8sPipelineClient k8s) {
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
     *
     * <p>⚠️ <b>走的必须是那个窄投影端点</b>（{@code POST /storageclasses/provisioners}）：
     * 通用的 {@code /storageclasses/list} 边界是 {@code PLATFORM}，租户调用方一律 403；而列集群级
     * StorageClass 又必须用 admin client。2026-10-15 修的就是这个 —— 在那之前这里调的是
     * {@code K8sClient.listAll}（→ {@code /storageclasses/list-all}，**集群级资源根本没有这个端点**），
     * 于是每次都抛异常、被下面的 catch 吞掉、恒返回 false：**所有人看到的都是
     * 「该集群没有可动态供应的 StorageClass」**，不管集群里有什么。
     */
    private boolean hasDynamicStorageClass(String clusterId) {
        try {
            return k8s.storageClassProvisioners(clusterId).stream().anyMatch(sc -> {
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
