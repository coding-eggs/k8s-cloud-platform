package com.coding.platformapi.models.overview;

import com.coding.platformapi.models.overview.PlatformOverviewView.Namespaces;
import com.coding.platformapi.models.overview.PlatformOverviewView.Nodes;
import com.coding.platformapi.models.overview.PlatformOverviewView.Pods;
import com.coding.platformapi.models.overview.PlatformOverviewView.Quota;
import com.coding.platformapi.models.overview.PlatformOverviewView.Workloads;

/**
 * 某个范围（集群 / 命名空间 / 租户）概览页上新增的计数卡数据。
 * <ul>
 *   <li>四个计数块：调用者没有对应读权限 → <b>null</b>（前端不渲染该卡，而不是显示 0）。</li>
 *   <li>{@code pods / workloads / quota}：仅命名空间范围有值（集群 / 租户页已有自己的概览数据）。</li>
 *   <li>{@code nodes / namespaces}：仅集群范围；{@code namespaceCount / memberCount}：仅租户范围。</li>
 * </ul>
 */
public record ScopeSummaryView(
        CountBreakdown applications,
        CountBreakdown middleware,
        CountBreakdown pipelines,
        CountBreakdown alerts,
        Pods pods,
        Workloads workloads,
        Quota quota,
        Nodes nodes,
        Namespaces namespaces,
        Long namespaceCount,
        Long memberCount) {
}
