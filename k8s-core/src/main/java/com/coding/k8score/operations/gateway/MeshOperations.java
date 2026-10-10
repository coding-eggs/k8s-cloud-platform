package com.coding.k8score.operations.gateway;

import com.coding.common.models.k8s.dto.admin.AdminMeshProbeResult;
import io.fabric8.kubernetes.api.model.apps.DaemonSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 服务网格探测的 <b>资源 probe</b> 那一半：查 ztunnel DaemonSet 是否存在。非资源 CRUD，故不套 Operations 接口。
 *
 * <h2>capability 那半已上移 platform-api（2026-10-10）</h2>
 * 本类以前还有一半职责：读 {@code k8s_cluster.capability} 快照 → {@code hasGatewayApi} /
 * {@code gatewayApiVersions} / {@code hasIstio}。那是<b>平台侧 DB 列</b>的派生（不是 K8s API 能直接回答的问题），
 * 而且它在 platform-api 的 {@code ClusterCapabilityService} 里另有一份实现 —— 同一判据两处实现迟早漂移。
 * 现在判据只在 {@code ClusterCapabilityService} 一处，本类只剩"探测集群上有没有 ztunnel"这件 K8s 侧的事。
 * <p>ambient 之所以仍留在适配器侧：ztunnel 是<b>某类工作负载存不存在</b>，不是一个 API group，塞不进 capability
 * 快照（混进去会让快照含义不可靠，且 ambient 随安装/卸载变化而 discovery 不会）。
 *
 * <h2>降级口径（信息性，不抛错）</h2>
 * ztunnel 探测的任何失败（istio-system 不存在、无权限、集群断开）一律记 warn 并返回 {@code false}
 * —— 它是横幅上的一个信息位，不是门禁。硬门禁 {@code hasGatewayApi} 读 capability 列，与本类无关。
 */
@Slf4j
@RequiredArgsConstructor
public class MeshOperations {

    /** Istio 控制面默认命名空间（ztunnel DaemonSet 的常见落点，但可配 —— 故有全 ns fallback） */
    private static final String ISTIO_SYSTEM = "istio-system";

    /** ambient 数据面 DaemonSet 名 */
    private static final String ZTUNNEL = "ztunnel";

    private final KubernetesClient client;

    /**
     * ambient 活探测结果。探测失败按 {@code false}（信息性降级，见类注释），不会抛错。
     */
    public AdminMeshProbeResult ambient() {
        AdminMeshProbeResult result = new AdminMeshProbeResult();
        result.setIstioAmbient(probeAmbient());
        return result;
    }

    /**
     * ambient 探测：先查 {@code istio-system/ztunnel}（绝大多数安装的落点），未命中则全命名空间搜同名
     * DaemonSet（Istio 允许自定义控制面命名空间）。两者都失败或抛错 → false。
     * <p>{@link #ambient()} 是唯一调用点，且探测本身便宜（一次 get，最多一次 list），故不做"没装 Istio 就不查"的短路。
     */
    private boolean probeAmbient() {
        try {
            DaemonSet named = client.apps().daemonSets()
                    .inNamespace(ISTIO_SYSTEM)
                    .withName(ZTUNNEL)
                    .get();
            if (named != null) {
                return true;
            }
        } catch (Exception e) {
            log.warn("探测 {}/{} DaemonSet 失败（按未安装 ambient 处理）：{}", ISTIO_SYSTEM, ZTUNNEL, e.getMessage());
        }
        try {
            return client.apps().daemonSets().inAnyNamespace().list().getItems().stream()
                    .anyMatch(ds -> ZTUNNEL.equals(ds.getMetadata() == null ? null : ds.getMetadata().getName()));
        } catch (Exception e) {
            log.warn("全命名空间搜索 {} DaemonSet 失败（按未安装 ambient 处理）：{}", ZTUNNEL, e.getMessage());
            return false;
        }
    }

}
