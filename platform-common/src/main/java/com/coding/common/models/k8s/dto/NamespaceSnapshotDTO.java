package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 一个命名空间的**全部事实**（租户概览/集群明细/命名空间页共用的一行 + 它的异常与不健康清单）。
 * <p>作用域判据：凡带 namespace 的东西都归这里（异常 Pod、不健康工作负载都带），集群键里不放。
 */
@Data
public class NamespaceSnapshotDTO {

    private NamespaceStatDTO stat;

    /** 该 ns 异常 Pod 总数（**全量**计数，不是名单条数） */
    private int abnormalPodCount;

    /** 该 ns 的 reason 分组计数（同样全量） */
    private Map<String, Integer> abnormalPodReasonCounts;

    /** 名单（沿用既有的 severity 排序 + 每 ns 封顶 ABNORMAL_POD_CAP） */
    private List<AbnormalPodDTO> abnormalPods;

    private int unhealthyWorkloadCount;

    private List<UnhealthyWorkloadDTO> unhealthyWorkloads;

    /** 采样时刻：epoch 毫秒（缓存的陈旧上界与页内一致性阈值都基于它） */
    private long sampledAt;
}
