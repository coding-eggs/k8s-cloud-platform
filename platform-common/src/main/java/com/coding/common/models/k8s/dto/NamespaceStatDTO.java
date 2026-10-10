package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 单个命名空间的资源明细行（集群概览「资源明细」tab / per-ns 用量）。
 * <p>口径：cpu=核、memory=字节（{@link BigDecimal} 基础单位）；allocated = {@code cpuRequest/memRequest}。
 * <p>{@code quota*} 来自该命名空间的 ResourceQuota（B3 已落地）：固定取 {@code requests.cpu} /
 * {@code requests.memory} 两个 key —— 与平台配额编辑器的口径同一对 key（见 {@code CoreV1ResourceQuotaConverter}）。
 * 无 ResourceQuota 时为 {@code null}（前端显示「—」），不是 0。
 * 一个 ns 有多份 ResourceQuota 时求和（实测罕见；两份同 key 的 quota 语义上互不叠加，此处取近似）。
 */
@Data
public class NamespaceStatDTO {

    private String namespace;

    private int podCount;
    private int deployCount;
    private int stsCount;
    private int dsCount;
    private int svcCount;
    private int pvcCount;

    /** Σ pod requests.cpu（核） */
    private BigDecimal cpuRequest;
    /** Σ pod requests.memory（字节） */
    private BigDecimal memRequest;
    /** Σ pod limits.cpu（核） */
    private BigDecimal cpuLimit;
    /** Σ pod limits.memory（字节） */
    private BigDecimal memLimit;

    /** ResourceQuota status.hard["requests.cpu"]（核）；无 quota → null */
    private BigDecimal quotaCpuHard;
    /** ResourceQuota status.hard["requests.memory"]（字节）；无 quota → null */
    private BigDecimal quotaMemHard;
    /** ResourceQuota status.used["requests.cpu"]（核）；无 quota → null */
    private BigDecimal quotaCpuUsed;
    /** ResourceQuota status.used["requests.memory"]（字节）；无 quota → null */
    private BigDecimal quotaMemUsed;
}
