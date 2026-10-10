package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * 集群 API 能力摘要（概览页「组件能力」tag 组）。
 * <p>全部由 {@code k8s_cluster.capability}（运行时 discovery 快照：API group → served versions）派生，
 * <b>不新增探测</b>：有该 group 即 true。capability 为空 = 「未探测」，此时七个 flag 全 false
 * （前端据此提示「能力未探测，可点刷新能力」，而不是断言「没装」）—— 与 {@link MeshStatusDTO}
 * 的 hasGatewayApi 口径一致。
 * <p>与 {@link MeshStatusDTO} 的关系：hasGatewayApi / hasIstio 两者同源同判据（同一份 capability 列）。
 * 本 DTO 是概览页要一次拿全七个 flag 的宽投影；细分版本的（gatewayApiVersions / istioAmbient 活探测）
 * 仍看 mesh-status。
 */
@Data
public class CapabilitySummaryDTO {

    /** {@code metrics.k8s.io}（metrics-server）：HPA 的资源指标来源 */
    private boolean hasMetricsServer;
    /** {@code custom.metrics.k8s.io}（适配器，如 Prometheus Adapter）：HPA 的自定义指标 */
    private boolean hasCustomMetrics;
    /** {@code external.metrics.k8s.io}：HPA 的外部指标 */
    private boolean hasExternalMetrics;
    /** {@code monitoring.coreos.com}（Prometheus Operator）：ServiceMonitor / PodMonitor 的宿主 */
    private boolean hasMonitoringOperator;
    /** {@code gateway.networking.k8s.io}：Gateway API（B6） */
    private boolean hasGatewayApi;
    /** {@code istio.io} 或 {@code networking.istio.io}：Istio（B6） */
    private boolean hasIstio;
    /** {@code crd.projectcalico.org}：Calico CRD（IPAM 派生视图，B7） */
    private boolean hasCalico;
}
