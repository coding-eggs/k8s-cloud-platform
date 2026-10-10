package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * 节点健康合计（概览首屏那一行「x / y Ready」）。
 * <p>{@code notReady} 含 Ready=Unknown 与条件缺失的节点（见 {@link NodeHealthDTO#isReady()}）——
 * 「不是 Ready」就是「不可调度」，不细分。
 */
@Data
public class NodeSummaryDTO {

    private int total;
    private int ready;
    private int notReady;
}
