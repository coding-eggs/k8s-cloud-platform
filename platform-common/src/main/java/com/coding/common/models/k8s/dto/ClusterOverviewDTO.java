package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 集群概览（首屏）响应：{@code POST /cluster/overview} 的出参。
 * <p>一次返回「总量快照 + 健康/异常 + 存储 + 能力」，<b>不灌 per-ns 全量</b>（那是
 * {@code /cluster/resource-breakdown} 懒加载的事）。
 * <p><b>降级约定（重要）：聚合来源不可用时，聚合派生段是 {@code null}（不是 0、不是空数组）</b>
 * —— 「集群未连接，读不到」与「真的一个异常 Pod 都没有」在运维上是相反的结论，
 * 前端必须把 null 渲染成「—」，不能把 null 当 0 展示。各段与此字段的对应关系：
 * <ul>
 *   <li>聚合段（resourceTotal / resourceCapacity / nodeSummary / unhealthyNodes / storage /
 *       abnormalPods+abnormalPodTotal）—— 同一次 {@code resource-aggregate} 调用，要么全有要么全 null。</li>
 *   <li>基本信息（名称/版本/...）不在这里：走 {@code /cluster/get}，只读 DB，集群断开也照常可见。</li>
 *   <li>capabilitySummary 始终有值（读 DB 的 capability 列，与集群连通性无关）；
 *       七 flag 全 false = 未探测。</li>
 * </ul>
 */
@Data
public class ClusterOverviewDTO {

    /** 节点健康合计（total / ready / notReady） */
    private NodeSummaryDTO nodeSummary;

    /**
     * 需要关注的节点：<b>未 Ready 或有 pressure</b> 的那些（不是字面上的「notReady 列表」）。
     * <p>并入 pressure 节点是因为首屏的价值在于「现在该看哪里」—— 一个 Ready 但 DiskPressure=True
     * 的节点同样在丢调度。逐节点用 {@link NodeHealthDTO#isReady()} / {@code pressures} 自辨类型。
     */
    private List<NodeHealthDTO> unhealthyNodes;

    /** Σ node.status.allocatable（allocatable 口径的分母） */
    private ResourceCapacityDTO resourceCapacity;

    /** allocated 口径 + 对象计数（取聚合的 total 行） */
    private ClusterTotalDTO resourceTotal;

    /** 能力摘要（capability 列派生，始终非 null） */
    private CapabilitySummaryDTO capabilitySummary;

    private StorageStatDTO storage;

    /** 异常 Pod 明细（封顶 200 条，按严重度降序） */
    private List<AbnormalPodDTO> abnormalPods;
    /** 异常 Pod 总数（未封顶）；null = 聚合不可用 */
    private Integer abnormalPodTotal;

    /** 异常 Pod 按 reason 分组计数（**全量**，不受 200 条封顶影响），按严重度降序；null = 聚合不可用 */
    private Map<String, Integer> abnormalPodReasonCounts;

    /** 不健康工作负载（{@code readyReplicas < replicas} 的 Deploy/Sts），封顶 200 条，缺得最多的在前 */
    private List<UnhealthyWorkloadDTO> unhealthyWorkloads;
    /** 不健康工作负载总数（未封顶）；null = 聚合不可用 */
    private Integer unhealthyWorkloadTotal;
}
