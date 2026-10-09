package com.coding.common.models.k8s.dto;

import lombok.Data;

/**
 * Route → 父资源引用（{@code spec.parentRefs[]} 项，Gateway API v1 {@code shared_types.go} 的 ParentReference）。
 * <p>HTTPRoute / GRPCRoute / TCPRoute / TLSRoute / UDPRoute 共用，故独立成顶层 DTO。
 * <p>缺省语义：{@code group}=gateway.networking.k8s.io、{@code kind}=Gateway、{@code namespace}=本 Route 所在命名空间。
 * 跨命名空间引用是否被接受由父 Gateway 的 {@code listeners[].allowedRoutes} 决定，不由平台 CRUD 边界决定。
 */
@Data
public class ParentRefDTO {

    /** 被引用资源的 group；缺省 gateway.networking.k8s.io（指向 Service 时须显式空串 ""） */
    private String group;

    /** 被引用资源的 kind；缺省 Gateway */
    private String kind;

    /** 被引用资源所在命名空间；缺省 = 本 Route 所在命名空间 */
    private String namespace;

    /** 被引用资源名（必填） */
    private String name;

    /** 目标 Gateway 的 listener 名（{@code sectionName}）；缺省 = 全部匹配的 listener */
    private String sectionName;

    /** 目标端口；缺省 = 该父资源上全部匹配的 listener。与 sectionName 同时给出时两者都要匹配 */
    private Integer port;

}
