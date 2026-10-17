package com.coding.platformapi.services;

import com.coding.common.models.pipeline.PipelineCacheStatusDTO;
import com.coding.common.models.pipeline.TektonSchedulingDTO;
import com.coding.platformapi.k8s.K8sPipelineClient;
import com.coding.platformapi.models.ComponentFlagDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * CI 能不能用 —— **整块可用性的唯一判据**（spec §9「capability 门控」）。
 *
 * <p>两个前提缺一不可：
 * <ol>
 *   <li><b>集群装了 Tekton</b> —— 没有 {@code tekton.dev} 就没有 {@code PipelineRun} 这个资源类型。
 *       判据来自 {@link ClusterComponentService#flags} 的 {@code ci}（S1.2 收口后与组件 tab /
 *       网格 / 网络 / HPA <b>同一份派生实现</b>；收口前读 {@link ClusterCapabilityService#hasTekton}）。</li>
 *   <li><b>有可动态供应的 StorageClass</b> —— 隐式共享 workspace 用 {@code volumeClaimTemplate}，
 *       它要现开一块 PVC（spec §3.3）。全是 {@code kubernetes.io/no-provisioner} 的静态盘
 *       会让每次运行都卡在绑定等待上。⚠️ <b>这一条有意不并进组件清单</b>：StorageClass 是集群存储配置，
 *       粒度是 SC 不是组件。</li>
 * </ol>
 *
 * <p>⚠️ 门控的意义是**整块不可用而不是报 500**：前端拿这个结果把「运行」按下去并说明原因
 * （部署后待验清单第一条）。所以它回的是「能不能用 + 为什么不能」，不是一个布尔。
 *
 * <p>⚠️ <b>这里探的只是「有没有」；集群侧还有两类**探不到**的前提</b>（Tekton 的
 * {@code coschedule} 是否为 {@code pipelineruns}、配额要求的默认容器资源）——
 * 它们在纳管集群时由人核对，清单见 {@code docs/development/cluster-prerequisites.md}。
 * 缺了不会让整块 CI 不可用，而是让**构建缓存失效**或让运行一秒内失败且没有日志。
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

    private final ClusterComponentService componentService;
    private final K8sPipelineClient k8s;

    public PipelineCapabilityService(ClusterComponentService componentService, K8sPipelineClient k8s) {
        this.componentService = componentService;
        this.k8s = k8s;
    }

    /**
     * 这个集群能不能跑 CI —— 外加**构建缓存**的状态。
     *
     * <p>⚠️ 缓存那一段与「能不能跑」是**两件事**：缓存关系到"快不快"（没了就每次都重下依赖），
     * 而 CI 照样能跑。所以缓存探不到时只是不回这一段（前端显示「—」），
     * **绝不因此把 available 按下去**。
     *
     * @param namespace 构建缓存是**按命名空间**的（每 ns 一块长期卷）；留空 = 不回缓存那一段
     */
    public Capability available(String clusterId, String tenantId, String namespace) {
        // Tekton 那一半改读组件旗标（S1.2 收口）：ci 依赖 tekton 与 tekton-operator，两个都好才叫能跑
        ComponentFlagDTO ci = componentService.flags(clusterId).get("ci");
        if (ci == null || !ci.available()) {
            String reason = ci == null || ci.reason() == null || ci.reason().isBlank()
                    ? "该集群未安装 Tekton（没有 tekton.dev 资源）"
                    : ci.reason();
            return new Capability(false, reason, null);
        }
        if (!hasDynamicStorageClass(clusterId)) {
            return new Capability(false,
                    "该集群没有可动态供应的 StorageClass —— 共享 workspace 需要现开一块 PVC", null);
        }
        return new Capability(true, null, cacheStatus(clusterId, tenantId, namespace));
    }

    /** 只问「能不能跑」时的形态（不回缓存状态）。 */
    public Capability available(String clusterId) {
        return available(clusterId, null, null);
    }

    private PipelineCacheStatusDTO cacheStatus(String clusterId, String tenantId, String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return null;
        }
        try {
            // 两件事分开问：卷的状态在 k8s-server 里（它是卷的主人），集群前提是另一个窄投影
            return withPrerequisite(k8s.cacheStatus(clusterId, tenantId, namespace),
                    k8s.tektonScheduling(clusterId));
        } catch (Exception e) {
            log.warn("探测集群 {} 的命名空间 {} 的构建缓存失败（只影响缓存那一行）", clusterId, namespace, e);
            return null;
        }
    }

    /**
     * 把「集群前提」那一段拼上。
     *
     * <p>⚠️ 前提不满足时**缓存一定用不了，哪怕那块卷好好地在那儿** —— 这种"卷没问题却用不了"的
     * 形态最容易让人以为平台坏了，所以两件事分开报（{@code exists} 照实回 true，{@code ready} 为 false）。
     */
    static PipelineCacheStatusDTO withPrerequisite(PipelineCacheStatusDTO pvc, TektonSchedulingDTO sched) {
        if (sched == null) {
            // 没探到 ≠ 探到了"没开" —— 前者是我们这边没拿到事实，别编一个值出来
            return new PipelineCacheStatusDTO(false, pvc.exists(), pvc.claimName(), pvc.capacity(),
                    "没探到集群的 Tekton 调度配置，按不支持处理（构建缓存不会启用）",
                    "Tekton coschedule = 未知", false);
        }
        boolean ok = sched.multiPvcWorkspacesAllowed();
        String value = sched.coschedule();
        String prerequisite = ok
                ? "Tekton coschedule = " + value
                : "Tekton coschedule = " + value + " —— 一个 TaskRun 挂不了两块 PVC workspace";
        if (!ok) {
            return new PipelineCacheStatusDTO(false, pvc.exists(), pvc.claimName(), pvc.capacity(),
                    "集群的 Tekton 没开 coschedule=pipelineruns，所以构建缓存启用不了"
                            + "（运行会自动退化成不共享，照样能跑）。这是集群侧配置，"
                            + "要让集群属主改 feature-flags",
                    prerequisite, false);
        }
        return new PipelineCacheStatusDTO(pvc.ready(), pvc.exists(), pvc.claimName(), pvc.capacity(),
                pvc.reason(), prerequisite, true);
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

    /**
     * @param reason 不可用时**必须**给原因 —— 不然用户只看到"功能没了"
     * @param cache  构建缓存的状态；{@code null} = 没问/没探到（前端显示「—」，不当作"不可用"）
     */
    public record Capability(boolean available, String reason, PipelineCacheStatusDTO cache) {
    }
}
