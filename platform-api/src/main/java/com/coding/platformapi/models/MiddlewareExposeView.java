package com.coding.platformapi.models;

import java.util.List;

/**
 * 中间件实例的对外访问方式。
 *
 * <p>unsupportedReason 非空 = 整个实例不支持（endpoints 为空）；gatewayUnavailableReason 非空 = 网关方式当前不可用
 * （端口池没配 / 组件未装 / Istio 组件变更中），NodePort 不受影响；unauthenticated = 实例没开鉴权，开暴露前要确认。
 */
public record MiddlewareExposeView(String unsupportedReason, String gatewayUnavailableReason, boolean unauthenticated,
                                   List<Endpoint> endpoints) {

    /**
     * mode：NONE / GATEWAY / NODEPORT；port：网关端口或 nodePort（NONE 为 null）；target：集群内 Service:端口。
     * programmed / conflicted / reason 只在 GATEWAY 时有（null = 状态未知）。
     */
    public record Endpoint(String key, String label, String target, String protocol, String mode, Integer port,
                           Boolean programmed, Boolean conflicted, String reason) {
    }
}
