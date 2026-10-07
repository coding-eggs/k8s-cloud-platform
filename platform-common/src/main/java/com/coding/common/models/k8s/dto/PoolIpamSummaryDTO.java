package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * IPPool 的 IPAM 派生汇总（非 CRD，k8s-core 计算）。
 * <p>计数一律 long（IPv6 容量大；超 Long.MAX_VALUE 时饱和封顶，不溢出）。
 * free = capacity − allocated − reserved（含未物化块地址空间）；blockCount = 已物化（非软删）块数。
 */
@Data
public class PoolIpamSummaryDTO {

    private String poolName;
    private String cidr;
    /** 池的块前缀长度（null=Calico 默认） */
    private Integer blockSize;
    /** 整池可分配地址总数（含未物化块；纯 CIDR 算术，溢出饱和） */
    private long capacity;
    /** 已分配 IP 数（仅来自已物化块） */
    private long allocated;
    /** 空闲 IP 数 = capacity − allocated − reserved（含未物化块空间） */
    private long free;
    /** 被保留段占用的 IP 数（池 CIDR ∩ 保留段，纯区间算术） */
    private long reserved;
    /** 已物化（非软删）块数量 */
    private int blockCount;

}
