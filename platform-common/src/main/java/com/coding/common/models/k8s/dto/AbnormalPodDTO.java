package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * 异常 Pod 一行（集群概览「健康/异常」摘要的展开明细）。
 * <p>判定口径见 {@code ClusterAggregationOperations}（与实现同源，只此一处）：
 * {@code phase ∈ {Pending, Failed, Unknown}}，或任一容器（含 init）waiting ∈
 * {CrashLoopBackOff, ImagePullBackOff, ErrImagePull} / terminated ∈ {OOMKilled, Error}。
 * <p>{@code reason} = 该 pod 命中的最严重那个（CrashLoopBackOff &gt; OOMKilled &gt; ImagePullBackOff &gt;
 * ErrImagePull &gt; Error &gt; Failed &gt; Unknown &gt; Pending）—— 前端按它分组计数，点开就是本 DTO 的列表。
 */
@Data
public class AbnormalPodDTO {

    private String namespace;
    private String name;

    /** pod.status.phase（Pending / Running / Failed / Unknown / Succeeded） */
    private String phase;
    /** 归组用的异常原因（最严重那个），非 K8s 原生字段 */
    private String reason;
    /** Σ 全部容器（含 init）的 restartCount */
    private int restarts;
    /** pod.spec.nodeName（未调度时为空） */
    private String node;
    /** 创建至今秒数（creationTimestamp 解析失败为 null） */
    private Long ageSeconds;
}
