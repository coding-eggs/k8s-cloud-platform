package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 集群资源总量（{@link ClusterAggregateDTO#getTotal()}，「Σ 出来的那一行」）。
 * <p>口径：cpu=核、memory=字节，均为基础单位 {@link BigDecimal}（前端负责格式化）。
 * <p>allocated 三态中的 <b>allocated</b> 就是这里的 {@code cpuRequest/memRequest}（K8s 实际调度口径 = requests）。
 * 另记 limits（超卖观测用）。{@code namespaceCount} = 本次聚合里出现过的命名空间数
 * （有平台可见对象的命名空间；不含"空空如也"的 ns，与 per-ns 明细表行数一致）。
 */
@Data
public class ClusterTotalDTO {

    /** Σ pod.spec.containers[].resources.requests.cpu（核） */
    private BigDecimal cpuRequest;
    /** Σ pod.spec.containers[].resources.requests.memory（字节） */
    private BigDecimal memRequest;
    /** Σ pod.spec.containers[].resources.limits.cpu（核） */
    private BigDecimal cpuLimit;
    /** Σ pod.spec.containers[].resources.limits.memory（字节） */
    private BigDecimal memLimit;

    private int podCount;
    private int deploymentCount;
    private int statefulsetCount;
    private int daemonsetCount;
    private int serviceCount;
    /** 出现过的命名空间数（pods/workloads/services/PVC/quota 任一有对象） */
    private int namespaceCount;
    private int pvcCount;
    private int pvCount;
}
