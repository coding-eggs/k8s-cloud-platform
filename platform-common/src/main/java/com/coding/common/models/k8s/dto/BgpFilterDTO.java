package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * Calico BGPFilter DTO（projectcalico.org/v3，集群级 CRD，<b>只读</b>）。
 * <p>真实 schema（v3.28 起）：spec 为四条规则列表 {@code exportV4/importV4/exportV6/importV6}
 * （早期 spec 稿里的 acceptPolicies/nodeSelector 设计不存在于 v3 API，勿用）。
 * 端点走 k8s-server {@code /calico/bgpfilter}，仅 list/get/yaml。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BgpFilterDTO extends BaseResources {

    /** spec.exportV4（导出方向 IPv4 规则，有序） */
    private List<BgpFilterRuleDTO> exportV4;

    /** spec.importV4（导入方向 IPv4 规则，有序） */
    private List<BgpFilterRuleDTO> importV4;

    /** spec.exportV6（导出方向 IPv6 规则，有序） */
    private List<BgpFilterRuleDTO> exportV6;

    /** spec.importV6（导入方向 IPv6 规则，有序） */
    private List<BgpFilterRuleDTO> importV6;

    /** status.conditions（只读；v3 无 status 时 null） */
    private List<ConditionDTO> conditions;

    /** 创建时间（仅查询返回，ISO-8601 字符串） */
    private String creationTime;

    @Override
    public String getApiPath() {
        return "/calico/bgpfilter";
    }

}
