package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.CapabilitySummaryDTO;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.k8s.K8sCluster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * 集群 API 能力判据的<b>唯一出处</b>：{@code k8s_cluster.capability} 列（JSON：group → versions[]）→ flag / versions。
 *
 * <p>为什么单独成类（2026-10-10）：这套派生以前有两份 —— 本类的前身（{@code ClusterService} 里）
 * 与 k8s-core 的 {@code MeshOperations}。两处各写一份「Gateway API 是哪个 group」「Istio 可能在哪两个 group 上」，
 * 迟早漂移。而它读的是<b>平台侧的 DB 列</b>（不是 K8s API 能直接回答的问题），所以归属是 platform-api，
 * 适配器侧不该派生业务 flag（见 docs/development/backend-layering.md §5）。
 *
 * <p>零推导的边界：本类只做「读列 + 反序列化 + 查 group 存不存在」，不含任何阈值/降级/缓存策略 ——
 * 那些是调用方的产品判断。空列/解析失败一律按<b>「未探测」</b>处理（全 false），不猜「没装」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClusterCapabilityService {

    /** Gateway API group（能力摘要与 mesh 横幅共用） */
    public static final String GATEWAY_API_GROUP = "gateway.networking.k8s.io";

    /** Istio 可能出现在两个 group 上：istio.io（IstioOperator 等）与 networking.istio.io（VirtualService 等） */
    public static final List<String> ISTIO_GROUPS = List.of("istio.io", "networking.istio.io");

    private static final String GROUP_METRICS_SERVER = "metrics.k8s.io";
    private static final String GROUP_CUSTOM_METRICS = "custom.metrics.k8s.io";
    private static final String GROUP_EXTERNAL_METRICS = "external.metrics.k8s.io";
    private static final String GROUP_MONITORING_OPERATOR = "monitoring.coreos.com";
    private static final String GROUP_CALICO_CRD = "crd.projectcalico.org";

    private final K8sClusterMapper clusterMapper;
    private final JsonMapper jsonMapper;

    /**
     * 整个 capability 快照（group → versions[]）。
     * 无行/空列/解析失败 → 空 map（前端据此判「未探测」，不硬阻断）。
     * 语义对齐 k8s-core {@code KubernetesOperationsFactory.readApiVersions}。
     */
    public Map<String, List<String>> groups(String clusterId) {
        K8sCluster cluster = clusterMapper.selectByPrimaryKey(clusterId);
        if (cluster == null) {
            return Map.of();
        }
        return parse(clusterId, cluster.getCapability());
    }

    /** 能力摘要：capability 列 → 七个 flag。有该 group 即 true；未探测（空列）→ 全 false。 */
    public CapabilitySummaryDTO summary(String clusterId) {
        Map<String, List<String>> cap = groups(clusterId);
        CapabilitySummaryDTO s = new CapabilitySummaryDTO();
        s.setHasMetricsServer(cap.containsKey(GROUP_METRICS_SERVER));
        s.setHasCustomMetrics(cap.containsKey(GROUP_CUSTOM_METRICS));
        s.setHasExternalMetrics(cap.containsKey(GROUP_EXTERNAL_METRICS));
        s.setHasMonitoringOperator(cap.containsKey(GROUP_MONITORING_OPERATOR));
        s.setHasGatewayApi(cap.containsKey(GATEWAY_API_GROUP));
        s.setHasIstio(anyIstio(cap));
        s.setHasCalico(cap.containsKey(GROUP_CALICO_CRD));
        return s;
    }

    /** 某 group 的 served versions（如 [v1, v1beta1]）；无该 group / 未探测 → 空列表。 */
    public List<String> versionsOf(String clusterId, String group) {
        if (!StringUtils.hasText(group)) {
            return List.of();
        }
        return groups(clusterId).getOrDefault(group, List.of());
    }

    /** 集群装了 Istio（capability 含 {@code istio.io} 或 {@code networking.istio.io}）。单取形式。 */
    public boolean hasIstio(String clusterId) {
        return anyIstio(groups(clusterId));
    }

    /** 「Istio 在不在」的唯一判据（{@link #summary} 与 {@link #hasIstio} 共用同一句）。 */
    private static boolean anyIstio(Map<String, List<String>> cap) {
        return ISTIO_GROUPS.stream().anyMatch(cap::containsKey);
    }

    /** capability JSON → group→versions；空列/解析失败 → 空 map（= 未探测）。 */
    private Map<String, List<String>> parse(String clusterId, String capabilityJson) {
        if (!StringUtils.hasText(capabilityJson)) {
            return Map.of();
        }
        try {
            return jsonMapper.readValue(capabilityJson,
                    new TypeReference<Map<String, List<String>>>() {});
        } catch (Exception e) {
            log.warn("解析集群 {} capability 失败（按未探测处理）: {}", clusterId, e.getMessage());
            return Map.of();
        }
    }
}
