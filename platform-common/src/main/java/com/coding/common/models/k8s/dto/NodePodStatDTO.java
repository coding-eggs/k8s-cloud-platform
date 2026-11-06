package com.coding.common.models.k8s.dto;

import lombok.Data;

/** 按节点聚合的 Pod 统计（列表页「实际 Pod 数 + 请求分配率」用）。 */
@Data
public class NodePodStatDTO {
    private String nodeName;
    /** 该节点上实际运行的 Pod 数 */
    private long podCount;
    /** sum(pod.cpu.requests)，毫核 */
    private long cpuRequestMillicores;
    /** sum(pod.memory.requests)，字节 */
    private long memRequestBytes;

    private long cpuLimitMillicores;

    private long memLimitBytes;

    /** Pod 状态分桶，口径同集群聚合（NamespaceStatDTO.podRunning 等）；podAbnormal = 其余全部 */
    private long podRunning;

    private long podPending;

    private long podSucceeded;

    private long podAbnormal;
}
