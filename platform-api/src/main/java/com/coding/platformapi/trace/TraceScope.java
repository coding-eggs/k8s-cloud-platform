package com.coding.platformapi.trace;

/**
 * 租户可见范围的一条分配：某集群下的某个命名空间。
 *
 * <p>列表/服务候选把它翻成 TraceQL 的 {@code resource.cluster.name + resource.k8s.namespace.name}
 * 条件；详情在平台侧按 span 的资源属性过滤（Tempo 的 trace-by-ID 只按 ID 返回整条 trace）。
 */
public record TraceScope(String clusterName, String namespace) {
}
