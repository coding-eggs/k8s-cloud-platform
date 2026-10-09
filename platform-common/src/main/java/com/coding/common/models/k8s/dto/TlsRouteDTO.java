package com.coding.common.models.k8s.dto;

import com.coding.common.models.k8s.BaseResources;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * TLSRoute DTO（{@code gateway.networking.k8s.io/{v1|v1alpha2}}，命名空间级、租户域）。
 * <p>与 TCP/UDPRoute 的唯一结构差别：多一个 {@code spec.hostnames}，即按 TLS ClientHello 的
 * <b>SNI</b> 选路由。
 *
 * <h2>SNI 匹配不在 rules 里（重要）</h2>
 * 旧实验 API 的形态是 {@code rules[].matches[].sniHostname}，<b>在 Gateway API 所有 v1.x 版本里都已不存在</b>
 * （v1.0.0 / v1.1.0 / v1.2.1 / v1.6.3 的 v1alpha2 type 文件里都搜不到 sniHostname）。现在的 CRD 是
 * {@code spec.hostnames[]} + {@code rules[]{name, backendRefs}} —— 本 DTO 按后者建模。
 * <p>此外 TLSRoute 常配 {@code tls.mode=Passthrough} 的监听器（不解密、只按 SNI 转发）；
 * 若配 Terminate，后端再加密走 BackendTLSPolicy（本模块 v1 不建模）。
 * <p>版本分派同 TCPRoute（见其类注释）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TlsRouteDTO extends BaseResources {

    /** spec.parentRefs */
    private List<ParentRefDTO> parentRefs;

    /** spec.hostnames（SNI 名；空 = 匹配全部 SNI） */
    private List<String> hostnames;

    /** spec.rules（CRD maxItems=16） */
    private List<L4RouteRuleDTO> rules;

    /** status.parents（只读） */
    private List<RouteParentStatusDTO> parentStatuses;

    /** 创建时间（仅查询返回，ISO-8601 字符串） */
    private String creationTime;

    @Override
    public String getApiPath() {
        return "/tlsroutes";
    }

}
