package com.coding.k8sserver.middleware;

/**
 * 一次用户操作的实例坐标。服务名 / Secret 名 / CR 名都从 {@code instanceName} 推导，
 * 与中间件适配器的命名口径保持一致。
 */
public record MiddlewareUserRef(String instanceName, String namespace, String instanceId) {
}
