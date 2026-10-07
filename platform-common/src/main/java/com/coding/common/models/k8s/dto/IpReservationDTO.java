package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * Calico IPReservation DTO（projectcalico.org/v3，集群级 CRD，无命名空间）。★保留 IP
 * <p>全 cluster-scoped、admin client、PLATFORM:admin 纵深防御；端点走 k8s-server {@code /admin/calico/ipreservation}。
 * name = RFC1123，创建后不可改（K8s 对象名）。保留项 = {@code spec.reservedCIDRs}：CIDR 字符串列表，
 * 单 IP 保留 = {@code "ip/32"}（v6 {@code /128}），范围保留 = {@code "cidr"}（可多条）。
 * <p><b>部署侧待确认</b>：group(v3) 与字段名 reservedCIDRs 以 tigera 文档为准（spec §11 风险）；实现按 projectcalico.org/v3。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class IpReservationDTO extends BaseResources {

    /** spec.reservedCIDRs：保留 CIDR 列表，如 {@code ["10.48.3.5/32", "10.48.3.64/28"]} */
    private List<String> reservedCidrs;

    /** 创建时间（仅查询返回，ISO-8601 字符串） */
    private String creationTime;

    @Override
    public String getApiPath() {
        return "/admin/calico/ipreservation";
    }

}
