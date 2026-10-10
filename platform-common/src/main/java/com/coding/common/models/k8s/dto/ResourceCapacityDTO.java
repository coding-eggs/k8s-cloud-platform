package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 节点可分配量合计（Σ {@code node.status.allocatable}）。
 * <p>「allocatable / allocated / used」三口径里的 <b>allocatable</b>：调度器真正能安排出去的量
 * （小于 capacity —— 系统预留、kube-reserved 已扣掉），所以超额/超卖比的分母用它。
 * <p>单位：cpu=核、memory=字节（{@link BigDecimal} 基础单位）。
 */
@Data
public class ResourceCapacityDTO {

    /** Σ node.status.allocatable.cpu（核） */
    private BigDecimal cpuAllocatable;
    /** Σ node.status.allocatable.memory（字节） */
    private BigDecimal memoryAllocatable;
}
