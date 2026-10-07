package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * BGPConfiguration spec.service{Cluster,External,LoadBalancer}IPs 单项（projectcalico.org/v3）。
 * <p>v3 CRD 仅 {@code cidr} 一个字段（旧 libapiconfig 的 blocked 标记未进 v3 schema）。
 */
@Data
public class BgpServiceBlockDTO {

    /** CIDR 块，如 {@code 10.96.0.0/12} */
    private String cidr;

}
