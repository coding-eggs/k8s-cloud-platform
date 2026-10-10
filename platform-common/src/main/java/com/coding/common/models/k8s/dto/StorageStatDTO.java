package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 存储统计（集群概览「资源用量」tab 的存储块 + 健康摘要的 Pending PVC 提示）。
 * <p>PV 一侧是容量（{@code spec.capacity.storage}）：{@code pvCapacityBytes} = 全部 PV 之和，
 * {@code pvBoundBytes} = 其中 phase=Bound 的那些之和。两者不等即为「已供给未绑定」的容量。
 * <p>PVC 一侧是状态计数：Bound / Pending / Lost。<b>Pending &gt; 0 是健康问题</b>（PVC 没等到 PV，
 * 工作负载会卡在 ContainerCreating），故它进概览首屏的异常摘要。
 * <p>容量单位 = 字节（{@link BigDecimal} 基础单位）。
 */
@Data
public class StorageStatDTO {

    private int pvCount;
    private int pvcBound;
    private int pvcPending;
    private int pvcLost;

    /** Σ PV spec.capacity.storage（字节） */
    private BigDecimal pvCapacityBytes;
    /** Σ 已 Bound PV 的容量（字节） */
    private BigDecimal pvBoundBytes;
}
