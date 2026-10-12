package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 租户概览首屏（`POST /tenant/overview`）。
 * <p>范围取自分配表（平台侧独有），事实取自各命名空间切片（缓存优先），合并只在
 * {@code TenantOverviewService} 一处做一次 —— 于是本 DTO 里的合计恒等于明细逐行相加（spec §6.1）。
 * <p>逐集群降级：某个集群取不到数时，它的行不进合计，但集群 id 出现在 {@link #unavailableClusters}，
 * 该集群的 {@link TenantClusterSummaryDTO#isAvailable()} 为 false —— 首屏显式提示，不整页失败。
 */
@Data
public class TenantOverviewDTO {

    private String tenantId;

    /** 本页各行（= 各命名空间切片）中**最旧**的采样时刻，epoch 毫秒；无数据 → 0（前端显示「数据截至」）。 */
    private long sampledAt;

    /** Σ 各行（= 本页明细逐行相加）——同屏"合计 = Σ明细"恒成立。 */
    private ClusterTotalDTO total;

    /** Σ 有配额的行的 hard（无任何配额行 → null，前端据此不给占用百分比）。 */
    private BigDecimal quotaCpuHard;
    private BigDecimal quotaMemHard;
    /** Σ 有配额的行的 used。 */
    private BigDecimal quotaCpuUsed;
    private BigDecimal quotaMemUsed;

    /** 没有任何 ResourceQuota 的命名空间名（{@code QuotaState.MISSING}）。 */
    private List<String> namespacesWithoutQuota;
    /** 有配额但缺 {@code requests.cpu} / {@code requests.memory} 任一 key 的命名空间名（{@code QuotaState.PARTIAL}）。 */
    private List<String> namespacesPartialQuota;

    /** 异常 Pod 总数（全量，未封顶）。 */
    private int abnormalPodTotal;
    /** reason 分组计数（全量求和，严重度降序）。 */
    private Map<String, Integer> abnormalPodReasonCounts;
    /** 异常 Pod 名单（跨集群合并后按 severity 排序 + 封顶 50）。 */
    private List<AbnormalPodDTO> abnormalPods;

    /** 不健康工作负载总数（全量，未封顶）。 */
    private int unhealthyWorkloadTotal;
    /** 不健康工作负载名单（跨集群合并后排序 + 封顶）。 */
    private List<UnhealthyWorkloadDTO> unhealthyWorkloads;

    /** 逐集群小计（含不可用的集群，其 {@code available=false}、{@code total=null}）。 */
    private List<TenantClusterSummaryDTO> clusters;

    /** 取数时节不可用的集群 id 列表（其余集群照常出数）。 */
    private List<String> unavailableClusters;
}
