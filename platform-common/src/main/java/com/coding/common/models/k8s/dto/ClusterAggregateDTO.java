package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 集群聚合快照（k8s-core {@code ClusterAggregationOperations.aggregate()} 的唯一输出）。
 * <p><b>为什么是这一个 DTO 而不是几个端点</b>：一次 {@code .inAnyNamespace().list()} pass 已经把所有对象
 * 读到手了（pods / workloads / services / quotas / PVC / PV / nodes），per-ns 行与总量行都是同一份内存
 * 数据的不同切面 —— 分成多个端点就要么重复 list、要么逐命名空间循环 list（大集群 O(ns) 次 API 调用）。
 * <p>两跳分工：k8s-server 只把本 DTO 原样回给 platform-api（零业务逻辑），
 * 展示规则（哪些段进 overview、哪些懒加载、降级成「—」）全在 platform-api 的 {@code ClusterService}。
 * <p><b>整体成功或整体失败</b>：本 DTO 由一次方法调用产出，任一次 list 失败即抛错（不静默返回半份数据
 * —— 「0 个 PVC」和「没权限看 PVC」在 UI 上是两个意思）。平台侧把它整段降级为 null + 前端「—」。
 */
@Data
public class ClusterAggregateDTO {

    /** 集群总量行（Σ 出来的那一行） */
    private ClusterTotalDTO total;

    /** per-namespace 明细行，按 namespace 升序 */
    private List<NamespaceStatDTO> namespaces;

    /** 异常 Pod 清单（按严重度降序，封顶 {@code ABNORMAL_POD_CAP} 条）；完整条数看 abnormalPodTotal */
    private List<AbnormalPodDTO> abnormalPods;
    /** 异常 Pod 总数（未封顶）。注意与 abnormalPods.size() 可能不等 —— UI 提示「仅显示前 N 条」 */
    private int abnormalPodTotal;
    /**
     * 异常 Pod 按 reason 分组计数（<b>全量</b>，不受封顶影响），按严重度降序、同严重度按计数降序。
     * <p>为什么单独给一份而不是让前端数 {@link #abnormalPods}：那是<b>截断后</b>的清单 —— 集群真有 500 个
     * 崩溃 Pod 时前端会算出 200，而这是首屏最显眼的一个数字。
     */
    private Map<String, Integer> abnormalPodReasonCounts;

    /** 不健康工作负载（{@code readyReplicas < replicas} 的 Deployment / StatefulSet），封顶同上 */
    private List<UnhealthyWorkloadDTO> unhealthyWorkloads;
    /** 不健康工作负载总数（未封顶） */
    private int unhealthyWorkloadTotal;

    /** 全部节点（含 Ready 的）健康投影，按 name 升序 */
    private List<NodeHealthDTO> nodeHealth;
    /** Σ node.status.allocatable（allocatable 口径的来源） */
    private ResourceCapacityDTO nodeCapacity;

    /** 存储统计（PV 容量 + PVC 状态计数） */
    private StorageStatDTO storage;
}
