package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.List;

/**
 * L4 路由（TCP / TLS / UDP）共用的一条规则：{@code {name, backendRefs}}。
 * <p>三者的 rule 在 CRD 里是同一个结构（{@code v1alpha2} 的 {@code TCPRouteRule}/{@code UDPRouteRule}
 * 在 v1.2+ 直接是 v1 类型的别名），故抽成顶层 DTO 共用，与 {@link ParentRefDTO}/{@link BackendRefDTO} 同一处理。
 *
 * <p><b>没有 matches</b>：L4 路由不做七层匹配。TLSRoute 的 SNI 匹配在 {@code spec.hostnames}（见
 * {@link TlsRouteDTO#getHostnames()}），<b>不是</b>旧实验 API 的 {@code rules[].matches[].sniHostname}
 * —— 后者在 Gateway API 所有 v1.x 版本里都已不存在。
 */
@Data
public class L4RouteRuleDTO {

    /** rule 名（可选，本 Route 内唯一） */
    private String name;

    /** 后端（必填，至少一个） */
    private List<BackendRefDTO> backendRefs;

}
