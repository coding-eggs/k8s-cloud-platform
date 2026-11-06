package com.coding.platformapi.models.overview;

import com.coding.platformapi.services.overview.PlatformHealthProbe.ServiceHealth;

import java.math.BigDecimal;
import java.util.List;

/**
 * 平台总览（{@code POST /overview/platform}）：按资源类型汇总全部<b>可用</b>集群，不按集群拆。
 * <p>null 语义：{@code capacity} = 没有任何可用集群；{@code quota} = 没有任何 ns 配了 hard。前端显示「—」/「未配置」。
 * <p>{@code byNamespace}：按（集群, 命名空间）拆开的 Pod 分桶与不健康工作负载数，供前端「点异常 → 跳到异常所在的命名空间」；
 * 只列有 Pod 或有不健康工作负载的命名空间；没有可用集群 → null。
 */
public record PlatformOverviewView(
        ServiceHealth services,
        Clusters clusters,
        CountBreakdown alertsFiring,
        CountBreakdown applications,
        CountBreakdown middleware,
        Workloads workloads,
        Pods pods,
        Tenants tenants,
        Capacity capacity,
        Nodes nodes,
        Namespaces namespaces,
        Quota quota,
        List<NamespaceCounts> byNamespace) {

    /** 已连接 / 不可用以聚合快照是否取得为准（不是 k8s_cluster.status 字段）。 */
    public record Clusters(int connected, int unavailable, List<String> unavailableNames) {
    }

    /** total = Deployment + StatefulSet + DaemonSet。 */
    public record Workloads(long total, long unhealthy) {
    }

    /** abnormal = total − running − pending − succeeded。 */
    public record Pods(long total, long running, long pending, long succeeded, long abnormal) {
    }

    public record Tenants(long total, long enabled, long disabled, long members, long allocatedNamespaces) {
    }

    /** requests ÷ allocatable 的分子分母（核 / 字节）；limits 合计与可分配比 = 超卖倍数。 */
    public record Capacity(BigDecimal cpuRequest, BigDecimal cpuAllocatable, BigDecimal memRequest, BigDecimal memAllocatable,
                           BigDecimal cpuLimit, BigDecimal memLimit) {
    }

    public record Nodes(long ready, long notReady) {
    }

    public record Namespaces(long allocated, long unallocated, long system) {
    }

    /** 一个（集群, 命名空间）的计数；口径同 {@link Pods}（abnormal = podCount − running − pending − succeeded）。 */
    public record NamespaceCounts(String clusterId, String clusterName, String namespace,
                                  long running, long pending, long succeeded, long abnormal, long unhealthyWorkloads,
                                  long middlewareNormal, long middlewareAbnormal, long middlewareProcessing) {
    }

    /** 只算配了 hard 的 ns（CPU 与内存各自独立判断）。 */
    public record Quota(BigDecimal cpuUsed, BigDecimal cpuHard, BigDecimal memUsed, BigDecimal memHard) {
    }
}
