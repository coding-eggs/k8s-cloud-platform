package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * UDPRoute DTO（{@code gateway.networking.k8s.io/{v1|v1alpha2}}，命名空间级、租户域）。
 * <p>与 {@link TcpRouteDTO} 结构完全一致（四层直转，只有 parentRefs + rules{name, backendRefs}），
 * 差别只在协议与 CRD kind。CRD 约束同样：{@code rules} 是 <b>maxItems=1</b> 的 atomic list。
 * <p>版本分派同 TCPRoute（见其类注释）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UdpRouteDTO extends BaseResources {

    /** spec.parentRefs */
    private List<ParentRefDTO> parentRefs;

    /** spec.rules（CRD maxItems=1） */
    private List<L4RouteRuleDTO> rules;

    /** status.parents（只读） */
    private List<RouteParentStatusDTO> parentStatuses;

    /** 创建时间（仅查询返回，ISO-8601 字符串） */
    private String creationTime;

    @Override
    public String getApiPath() {
        return "/udproutes";
    }

}
