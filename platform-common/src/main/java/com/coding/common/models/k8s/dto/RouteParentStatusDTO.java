package com.coding.common.models.k8s.dto;

import lombok.Data;

import java.util.List;

/**
 * Route 的 {@code status.parents[]} 项（Gateway API v1 的 RouteParentStatus）。
 * <p>5 类 Route（HTTP / GRPC / TCP / TLS / UDP）共用，故独立成顶层 DTO。
 * <p>只读：由 Gateway 控制器写入，平台不下发。
 */
@Data
public class RouteParentStatusDTO {

    /** 对应的父引用（与 spec.parentRefs 里的一条对应） */
    private ParentRefDTO parentRef;

    /** 写这条 status 的控制器名（= 该父 Gateway 的 GatewayClass.controllerName） */
    private String controllerName;

    /** Accepted / ResolvedRefs 等条件 */
    private List<ConditionDTO> conditions;

}
