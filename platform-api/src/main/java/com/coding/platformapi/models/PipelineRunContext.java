package com.coding.platformapi.models;

/**
 * 一次运行调用的上下文：跑在哪个集群的哪个命名空间里、以谁的身份。
 *
 * <p>它只承载**边界三要素**，不做任何判断 —— 分配表校验在 service 层
 * （与 {@code PipelineService.assertNamespaceAccessible} 同一套）。
 *
 * @param tenantId 平台管理员代操作时为 null（与 {@code AuthContext.hatTenantId()} 同一语义）
 */
public record PipelineRunContext(String clusterId, String namespace, String tenantId) {
}
