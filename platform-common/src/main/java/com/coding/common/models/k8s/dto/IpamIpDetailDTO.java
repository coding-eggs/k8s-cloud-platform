package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * 单块 per-IP 明细（blockIps 下钻；未物化块合成全 free）。
 * <p>status 三态：free（可用）/ reserved（被保留段占用，不自动分配）/ allocated（已分配给 Pod）。
 * allocated 时 podName/podNamespace 由 {@code pod.status.podIP} 反查；反查不到则 null（显示「—」）。
 * node 取块 affinity（{@code host:<node>}），未绑定为 null。
 */
@Data
public class IpamIpDetailDTO {

    /** IP 地址字符串 */
    private String ip;
    /** free / reserved / allocated */
    private String status;
    /** 占用该 IP 的 Pod 名（仅 allocated；反查不到为 null） */
    private String podName;
    /** 占用该 IP 的 Pod 命名空间（仅 allocated） */
    private String podNamespace;
    /** 块归属节点（affinity；null=未绑定） */
    private String node;

}
