package com.coding.platformapi.services;

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
 * {@code k8s_cluster.capability} 列（JSON：group → versions[]）的<b>唯一读取处</b>。
 *
 * <p>它只回答一件事：<b>这个集群有没有某个 API group、它的 served versions 是什么</b>。
 * 零推导的边界：只做「读列 + 反序列化 + 查 group」，不含任何阈值/降级/缓存策略 ——
 * 那些是调用方的产品判断。空列/解析失败一律按<b>「未探测」</b>处理，不猜「没装」。
 *
 * <p><b>与组件清单的分工（S1.2，2026-10-17）</b>：组件的四态（装了没有 / 版本 / 健康）由
 * {@code ClusterComponentService} 派生；本类剩下的用途是"API group 存在性"这一维 ——
 * 网格状态的 UNKNOWN 回退（{@link MeshService}）与 {@code /cluster/capability/get} 的原始快照。
 * 原先的七 flag 摘要与 {@code hasTekton} 在收口后已无调用方，本批删除（避免第二份判据）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClusterCapabilityService {

    /** Gateway API group（网格版本的唯一判据） */
    public static final String GATEWAY_API_GROUP = "gateway.networking.k8s.io";

    /** Istio 可能出现在两个 group 上：istio.io（IstioOperator 等）与 networking.istio.io（VirtualService 等） */
    public static final List<String> ISTIO_GROUPS = List.of("istio.io", "networking.istio.io");

    private final K8sClusterMapper clusterMapper;
    private final JsonMapper jsonMapper;

    /**
     * 整个 capability 快照（group → versions[]）。
     * 无行/空列/解析失败 → 空 map（调用方据此判「未探测」，不硬阻断）。
     * 语义对齐 k8s-core {@code KubernetesOperationsFactory.readApiVersions}。
     */
    public Map<String, List<String>> groups(String clusterId) {
        K8sCluster cluster = clusterMapper.selectByPrimaryKey(clusterId);
        if (cluster == null) {
            return Map.of();
        }
        return parse(clusterId, cluster.getCapability());
    }

    /** 某 group 的 served versions（如 [v1, v1beta1]）；无该 group / 未探测 → 空列表。 */
    public List<String> versionsOf(String clusterId, String group) {
        if (!StringUtils.hasText(group)) {
            return List.of();
        }
        return groups(clusterId).getOrDefault(group, List.of());
    }

    /** 集群装了 Istio（capability 含 {@code istio.io} 或 {@code networking.istio.io}）。 */
    public boolean hasIstio(String clusterId) {
        return anyIstio(groups(clusterId));
    }

    /** 「Istio 在不在」的唯一判据（本类与 MeshService 的回退共用同一句）。 */
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
