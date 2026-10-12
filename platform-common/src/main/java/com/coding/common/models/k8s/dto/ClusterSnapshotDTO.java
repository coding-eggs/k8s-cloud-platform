package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.List;

/**
 * 集群键（{@code cluster:agg:<clusterId>}）的值：<b>只放集群级事实</b>。
 * <p>节点健康 / 节点容量 / PV-存储 / 命名空间名索引 / 采样时刻 —— <b>不含任何 per-ns 数值、也不含合计</b>：
 * 带 namespace 字段的一切（行、异常 Pod、不健康工作负载）都落在各自的 ns 键
 * （{@link NamespaceSnapshotDTO}）里；合计由读取方用那些切片现算（「同一屏合计 = Σ明细」）。
 * <p>作用域判据：只有真正与命名空间无关的事实（节点、PV）才留在这里。
 */
@Data
public class ClusterSnapshotDTO {

    /** 全部节点（含 Ready 的）健康投影，按 name 升序 */
    private List<NodeHealthDTO> nodeHealth;

    /** Σ node.status.allocatable（allocatable 口径的来源） */
    private ResourceCapacityDTO nodeCapacity;

    /** 存储统计（PV 容量 + PVC 状态计数）—— cluster-scoped */
    private StorageStatDTO storage;

    /**
     * 本次聚合里出现过的命名空间名（升序）。<b>它只是索引</b>：「要 MGET 哪些 ns 键」。
     * 不要往这里塞任何 per-ns 数值。
     */
    private List<String> namespaceNames;

    /** 采样时刻：epoch 毫秒。集群键与本次写入的 N 个 ns 键<b>共享同一个值</b>（一次采样）。 */
    private long sampledAt;
}
