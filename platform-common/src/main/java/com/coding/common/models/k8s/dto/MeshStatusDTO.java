package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.List;

/**
 * 服务网格探测结果（mesh-status）：模块横幅与 create 门禁的数据源。
 * <p>不是 K8s 资源，也不是 {@code BaseResources} 子类 —— 它是<b>多来源聚合</b>的派生视图：
 * <ul>
 *   <li>{@code hasGatewayApi} / {@code gatewayApiVersions} / {@code hasIstio} —— 纯 discovery，
 *       读 {@code k8s_cluster.capability}（group → versions 快照，B2 起持久化），<b>不新增探测</b>。</li>
 *   <li>{@code istioAmbient} —— 资源 probe（非 CRD group）：查 ztunnel DaemonSet 是否存在
 *       （ambient 模式每节点一个 ztunnel）。探测失败一律 {@code false}，不报错（信息性降级）。</li>
 * </ul>
 * <p><b>为什么不把 ambient 写进 capability 列</b>：capability 是 discovery 快照（有哪些 API group），
 * ambient 是"某类工作负载存不存在"。混进去会让快照的含义变得不可靠，且 ambient 会随安装/卸载变化，
 * 而 discovery 不会。故 ambient 做成 on-demand + 短 TTL 缓存。
 * <p>{@code hasGatewayApi=false} 时前端整组 create 禁用 + 横幅提示（存量对象照常展示）。
 */
@Data
public class MeshStatusDTO {

    /** 集群装了 Istio（capability 含 {@code istio.io} 或 {@code networking.istio.io}）。信息性，不作门禁 */
    private boolean hasIstio;

    /** Istio 处于 ambient 模式（探测到 ztunnel DaemonSet）。信息性，探测失败即 false */
    private boolean istioAmbient;

    /** 集群装了 Gateway API（capability 含 {@code gateway.networking.k8s.io}）。<b>本模块的硬门禁</b> */
    private boolean hasGatewayApi;

    /** 该 group 的 served versions 快照（如 [v1, v1beta1, v1alpha2]），供 UI 展示与版本说明 */
    private List<String> gatewayApiVersions;

}
