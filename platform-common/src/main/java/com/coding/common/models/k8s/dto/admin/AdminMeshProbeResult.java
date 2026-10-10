package com.coding.common.models.k8s.dto.admin;

import lombok.Data;

/** 网格**活探测**结果（k8s-server → platform-api）：只含"资源 probe"那一半。
 *  <p>discovery 那一半（hasGatewayApi / hasIstio / gatewayApiVersions）是 {@code k8s_cluster.capability}
 *  列的派生，属 platform-api（2026-10-10 起）。 */
@Data
public class AdminMeshProbeResult {
    /** Istio 处于 ambient 模式（探测到 ztunnel DaemonSet） */
    private boolean istioAmbient;
}
