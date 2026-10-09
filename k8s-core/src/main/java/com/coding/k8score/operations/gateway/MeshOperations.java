package com.coding.k8score.operations.gateway;

import com.coding.common.models.k8s.dto.MeshStatusDTO;
import io.fabric8.kubernetes.api.model.apps.DaemonSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 服务网格探测（gateway.networking.k8s.io + Istio / ambient）。非资源 CRUD，故不套 Operations 接口。
 * <p>两个来源，粒度不同：
 * <ul>
 *   <li><b>discovery</b>（{@code hasGatewayApi} / {@code gatewayApiVersions} / {@code hasIstio}）——
 *       读 {@code k8s_cluster.capability} 快照（group → versions[]），<b>不新增探测</b>，与 B2 的能力读端点同源。</li>
 *   <li><b>资源 probe</b>（{@code istioAmbient}）—— 查 ztunnel DaemonSet 是否存在。ambient 模式每节点一个
 *       ztunnel，是"装没装 ambient"最直接的证据；而它<b>不是一个 API group</b>，塞不进 capability 快照
 *       （capability 的含义会因此变得不可靠，且 ambient 会随安装/卸载变化而 discovery 不会）。</li>
 * </ul>
 *
 * <h2>降级口径（信息性，不抛错）</h2>
 * ztunnel 探测的任何失败（istio-system 不存在、无权限、集群断开）一律记 warn 并返回 {@code false}
 * —— 它是横幅上的一个信息位，不是门禁。{@code hasGatewayApi=false} 才是门禁（前端据此禁用 create）。
 */
@Slf4j
@RequiredArgsConstructor
public class MeshOperations {

    /** Istio 控制面默认命名空间（ztunnel DaemonSet 的常见落点，但可配 —— 故有全 ns fallback） */
    private static final String ISTIO_SYSTEM = "istio-system";

    /** ambient 数据面 DaemonSet 名 */
    private static final String ZTUNNEL = "ztunnel";

    private static final String GATEWAY_API_GROUP = "gateway.networking.k8s.io";

    /** Istio 可能出现在两个 group 上：istio.io（IstioOperator 等）与 networking.istio.io（VirtualService 等） */
    private static final List<String> ISTIO_GROUPS = List.of("istio.io", "networking.istio.io");

    private final KubernetesClient client;

    /** {@code k8s_cluster.capability} 快照（group → versions[]）；未探测过时为空 Map */
    private final Map<String, List<String>> capability;

    /**
     * 汇总状态。capability 为空的语义是"未探测"而非"没装" —— 与 {@code useClusterCapability} 前端
     * composable 的口径一致：此时 hasGatewayApi=false（保守，前端提示"未探测/未安装"并给「刷新能力」按钮）。
     */
    public MeshStatusDTO status() {
        Map<String, List<String>> cap = capability == null ? Map.of() : capability;
        MeshStatusDTO dto = new MeshStatusDTO();
        dto.setHasGatewayApi(cap.containsKey(GATEWAY_API_GROUP));
        dto.setGatewayApiVersions(cap.getOrDefault(GATEWAY_API_GROUP, List.of()));
        dto.setHasIstio(ISTIO_GROUPS.stream().anyMatch(cap::containsKey));
        dto.setIstioAmbient(probeAmbient());
        return dto;
    }

    /**
     * ambient 探测：先查 {@code istio-system/ztunnel}（绝大多数安装的落点），未命中则全命名空间搜同名
     * DaemonSet（Istio 允许自定义控制面命名空间）。两者都失败或抛错 → false。
     * <p>只在 {@code hasIstio} 时才值得一查 —— 没装 Istio 的集群不可能有 ztunnel。这里不做那个短路：
     * {@link #status()} 是唯一调用点，且探测本身便宜（一次 get，最多一次 list）。
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

    /** 该 group 的 served versions（给 platform-api 侧做 L4 路由的版本分派用；缺失返回空列表）。 */
    public List<String> versionsOf(String group) {
        if (capability == null || !StringUtils.hasText(group)) {
            return List.of();
        }
        return capability.getOrDefault(group, List.of());
    }

}
