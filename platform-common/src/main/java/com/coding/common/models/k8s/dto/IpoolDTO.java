package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Calico IPPool DTO（projectcalico.org/v3，集群级 CRD，无命名空间）。
 * <p>全 cluster-scoped、admin client、PLATFORM:admin 纵深防御；端点走 k8s-server {@code /admin/calico/ippool}。
 * name = RFC1123，创建后不可改（K8s 对象名）。字段为建模核心集，精确字段名以 tigera 文档为准。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class IpoolDTO extends BaseResources {

    /** spec.cidr（必填），如 {@code 10.48.0.0/16} */
    private String cidr;

    /** spec.blockSize（可选，块前缀长度；null=Calico 默认 IPv4 /26、IPv6 /122） */
    private Integer blockSize;

    /** spec.nodeSelector（表达式列表，如 {@code projectcalico.org/node==worker}） */
    private java.util.List<String> nodeSelector;

    /** spec.natOutgoing（是否 NAT 出网） */
    private Boolean natOutgoing;

    /** spec.disabled（禁用后不再分配新 IP） */
    private Boolean disabled;

    /** spec.ipv4hierarchicalPortAllocation（IPv4 分层端口分配，BGP 场景） */
    private Boolean ipv4hierarchicalPortAllocation;

    /** spec.blocks（可选，指定使用的块 CIDR 列表） */
    private java.util.List<String> blocks;

    /** status.conditions（只读） */
    private java.util.List<ConditionDTO> conditions;

    /** 创建时间（仅查询返回，ISO-8601 字符串） */
    private String creationTime;

    @Override
    public String getApiPath() {
        return "/admin/calico/ippool";
    }

}
