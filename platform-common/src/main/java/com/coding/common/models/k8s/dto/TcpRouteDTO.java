package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * TCPRoute DTO（{@code gateway.networking.k8s.io/{v1|v1alpha2}}，命名空间级、租户域）。
 * <p>四层直转：把一个监听器端口上的 TCP 连接按权重分给若干后端，<b>不做任何七层解析</b>。
 * 结构最简单的一类 —— 只有 parentRefs + rules{name, backendRefs}。
 *
 * <h2>版本</h2>
 * v1.6 起 L4 路由与其它 Gateway API 对象一样 GA 于 {@code v1}；更早的 Gateway API 只在
 * {@code v1alpha2} 提供（两者在 CRD 里是同一结构的不同版本名，见 {@code L4RouteRuleDTO} 的注释）。
 * 具体用哪个版本由后端按集群 capability 分派（{@code KubernetesOperationsFactory.resolveL4Version}），
 * 前端与本 DTO 都不感知。
 *
 * <p>CRD 约束：{@code rules} 是 <b>maxItems=1</b> 的 atomic list（一条规则，不能多）——
 * 这是 L4 与 HTTPRoute 最直观的差别，前端表单只允许一条规则。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TcpRouteDTO extends BaseResources {

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
        return "/tcproutes";
    }

}
