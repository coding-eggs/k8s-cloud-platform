package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * 不健康工作负载一行（集群概览「工作负载」tab 的「副本不足」清单）。
 * <p>判定口径（B4 计划 §口径 3）：{@code status.readyReplicas < spec.replicas}，只算 Deployment / StatefulSet
 * （DaemonSet 没有 replicas 语义，不在此列）。
 * <p>{@code replicas} 缺省按 1 计（K8s 的 spec.replicas 可省，省略即 1）；{@code readyReplicas} 缺省按 0 计 ——
 * 所以"刚创建还没起来"的工作负载会正确地出现在这份清单里，而 {@code replicas=0}（有意缩容到零）不会。
 */
@Data
public class UnhealthyWorkloadDTO {

    private String namespace;
    private String name;
    /** Deployment / StatefulSet（K8s 原生 PascalCase，与 pod owner 的写法一致） */
    private String kind;
    private int readyReplicas;
    private int replicas;
}
