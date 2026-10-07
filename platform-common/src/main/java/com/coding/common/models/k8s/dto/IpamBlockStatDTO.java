package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * 单个已物化 IPAM 块的派生统计（默认块视图每块一行）。
 * <p>计数为三态划分：free + reserved + allocated = totalIps。
 * node 取自块 {@code spec.affinity}（{@code host:<node>}），未绑定节点时为 null。
 */
@Data
public class IpamBlockStatDTO {

    /** 块 CIDR（如 {@code 10.48.3.64/26}） */
    private String cidr;
    /** 归属节点（affinity；null=未绑定） */
    private String node;
    /** 块可分配地址总数（IPv4 去 network/broadcast） */
    private long totalIps;
    /** 已分配 IP 数 */
    private long allocated;
    /** 空闲且未被保留的 IP 数 */
    private long free;
    /** 空闲但被保留段占用的 IP 数（不计入 free） */
    private long reserved;

}
