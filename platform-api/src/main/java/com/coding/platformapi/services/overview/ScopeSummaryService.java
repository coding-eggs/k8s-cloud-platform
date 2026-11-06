package com.coding.platformapi.services.overview;

import com.coding.common.models.k8s.dto.ClusterSnapshotDTO;
import com.coding.common.models.k8s.dto.NamespaceSnapshotDTO;
import com.coding.common.models.k8s.dto.NamespaceStatDTO;
import com.coding.common.models.k8s.dto.NodeHealthDTO;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.auth.PlatformUserTenantMapper;
import com.coding.data.mapper.k8s.PlatformAlertEventMapper;
import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.mapper.system.PlatformPipelineMapper;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.platformapi.models.overview.CountBreakdown;
import com.coding.platformapi.models.overview.PlatformOverviewView.Namespaces;
import com.coding.platformapi.models.overview.PlatformOverviewView.Nodes;
import com.coding.platformapi.models.overview.PlatformOverviewView.Pods;
import com.coding.platformapi.models.overview.PlatformOverviewView.Quota;
import com.coding.platformapi.models.overview.PlatformOverviewView.Workloads;
import com.coding.platformapi.models.overview.ScopeSummaryView;
import com.coding.platformapi.services.ClusterService;
import com.coding.platformapi.services.MiddlewareService;
import com.coding.platformapi.services.NamespaceStatService;
import com.coding.platformapi.services.TenantOverviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.coding.platformapi.services.overview.OverviewPermissions.APPLICATION_READ;
import static com.coding.platformapi.services.overview.OverviewPermissions.MIDDLEWARE_READ;
import static com.coding.platformapi.services.overview.OverviewPermissions.PIPELINE_READ;
import static com.coding.platformapi.services.overview.OverviewPermissions.PLATFORM_ALERT_READ;
import static com.coding.platformapi.services.overview.OverviewPermissions.TENANT_ALERT_LIST;

/**
 * 集群 / 命名空间 / 租户概览页的计数卡（{@link ScopeSummaryView}）。
 * <p>四个计数块各自按原列表页的读权限放行（{@link OverviewPermissions}），没权限 → null。
 * 计数口径与平台总览同一套（{@link OverviewBuckets}），只是范围收窄：集群 = clusterId；
 * 命名空间 = clusterId + 单个 ns；租户 = 分配表里的 (cluster, ns 集合) 逐集群算后相加。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScopeSummaryService {

    private final ClusterService clusters;
    private final NamespaceStatService stats;
    private final MiddlewareService middleware;
    private final PlatformApplicationMapper apps;
    private final PlatformPipelineMapper pipelines;
    private final PlatformAlertEventMapper alerts;
    private final PlatformUserTenantMapper userTenants;
    private final PlatformTenantNamespaceMapper allocations;
    private final TenantOverviewService tenantOverview;
    private final OverviewPermissions permissions;

    public ScopeSummaryView cluster(String clusterId) {
        ClusterSnapshotDTO snap = clusters.snapshot(clusterId);
        Blocks b = blocks(Map.of(clusterId, ALL), null);
        if (snap == null) {
            // 集群连不上：中间件实时状态必然读不到，不拿台账值冒充「正常」
            b = new Blocks(b.apps, null, b.ci, b.alerts);
        }
        Nodes nodes = null;
        Namespaces namespaces = null;
        if (snap != null) {
            long ready = 0, notReady = 0;
            for (NodeHealthDTO n : orEmpty(snap.getNodeHealth())) {
                if (n.isReady()) {
                    ready++;
                } else {
                    notReady++;
                }
            }
            nodes = new Nodes(ready, notReady);
            Set<String> allocated = orEmpty(allocations.listAll()).stream()
                    .filter(a -> clusterId.equals(a.getClusterId()))
                    .map(PlatformTenantNamespace::getNamespace)
                    .collect(Collectors.toSet());
            long sys = 0, alloc = 0, unalloc = 0;
            for (String ns : orEmpty(snap.getNamespaceNames())) {
                switch (NamespaceCategory.of(ns, allocated)) {
                    case SYSTEM -> sys++;
                    case ALLOCATED -> alloc++;
                    case UNALLOCATED -> unalloc++;
                }
            }
            namespaces = new Namespaces(alloc, unalloc, sys);
        }
        return new ScopeSummaryView(b.apps, b.mw, b.ci, b.alerts, null, null, null, nodes, namespaces, null, null);
    }

    public ScopeSummaryView namespace(String clusterId, String namespace) {
        Blocks b = blocks(Map.of(clusterId, List.of(namespace)), null);
        Pods pods = null;
        Workloads workloads = null;
        Quota quota = null;
        NamespaceSnapshotDTO row = rowOf(clusterId, namespace);
        NamespaceStatDTO st = row == null ? null : row.getStat();
        if (st != null) {
            long abnormal = Math.max(0, st.getPodCount() - st.getPodRunning() - st.getPodPending() - st.getPodSucceeded());
            pods = new Pods(st.getPodCount(), st.getPodRunning(), st.getPodPending(), st.getPodSucceeded(), abnormal);
            workloads = new Workloads(st.getDeployCount() + st.getStsCount() + st.getDsCount(), row.getUnhealthyWorkloadCount());
            if (st.getQuotaCpuHard() != null || st.getQuotaMemHard() != null) {
                quota = new Quota(st.getQuotaCpuUsed(), st.getQuotaCpuHard(), st.getQuotaMemUsed(), st.getQuotaMemHard());
            }
        }
        return new ScopeSummaryView(b.apps, b.mw, b.ci, b.alerts, pods, workloads, quota, null, null, null, null);
    }

    public ScopeSummaryView tenant(String tenantId) {
        Map<String, List<String>> byCluster = tenantOverview.allocationsByCluster(tenantId);
        Blocks b = blocks(byCluster, tenantId);
        long nsCount = byCluster.values().stream().mapToLong(List::size).sum();
        return new ScopeSummaryView(b.apps, b.mw, b.ci, b.alerts, null, null, null, null, null,
                nsCount, userTenants.countDistinctUsers(tenantId));
    }

    /** {@code namespaces == ALL} 表示"整个集群不按 ns 过滤"（SQL 侧传 null）。 */
    private static final List<String> ALL = List.of("\0all");

    private record Blocks(CountBreakdown apps, CountBreakdown mw, CountBreakdown ci, CountBreakdown alerts) {
    }

    private Blocks blocks(Map<String, List<String>> byCluster, String tenantId) {
        boolean canApps = permissions.has(APPLICATION_READ);
        boolean canMw = permissions.has(MIDDLEWARE_READ);
        boolean canCi = permissions.has(PIPELINE_READ);
        boolean canAlerts = permissions.has(TENANT_ALERT_LIST) || permissions.has(PLATFORM_ALERT_READ);
        List<CountBreakdown> a = new ArrayList<>(), m = new ArrayList<>(), c = new ArrayList<>(), al = new ArrayList<>();
        byCluster.forEach((clusterId, nsList) -> {
            Collection<String> ns = nsList == ALL ? null : nsList;
            if (canApps) {
                a.add(OverviewBuckets.applications(orEmpty(apps.countByStatus(clusterId, ns))));
            }
            if (canCi) {
                c.add(OverviewBuckets.pipelines(orEmpty(pipelines.countLatestRunStatus(clusterId, ns))));
            }
            if (canAlerts) {
                al.add(OverviewBuckets.alerts(orEmpty(alerts.countFiringBySeverity(clusterId, ns))));
            }
            if (canMw) {
                m.add(middlewareOf(clusterId, ns, tenantId));
            }
        });
        return new Blocks(canApps ? OverviewBuckets.sum(a) : null, canMw ? OverviewBuckets.sum(m) : null,
                canCi ? OverviewBuckets.sum(c) : null, canAlerts ? OverviewBuckets.sum(al) : null);
    }

    /**
     * 按命名空间圈定后再按 tenantId 过滤：V2026_10_31_3 起一个命名空间只归一个租户，这一层只是防御。
     */
    private CountBreakdown middlewareOf(String clusterId, Collection<String> namespaces, String tenantId) {
        try {
            Set<String> nsSet = namespaces == null ? null : new HashSet<>(namespaces);
            Predicate<MiddlewareService.LiveStatus> keep = s ->
                    (nsSet == null || nsSet.contains(s.namespace()))
                            && (tenantId == null || tenantId.equals(s.tenantId()));
            return OverviewBuckets.middleware(orEmpty(middleware.liveStatuses(clusterId)).stream()
                    .filter(keep).map(MiddlewareService.LiveStatus::status).toList());
        } catch (Exception e) {
            log.warn("集群 {} 中间件状态不可用（概览计数不计入）：{}", clusterId, e.getMessage());
            return null;
        }
    }

    private NamespaceSnapshotDTO rowOf(String clusterId, String namespace) {
        try {
            return stats.row(null, clusterId, namespace);
        } catch (Exception e) {
            log.warn("命名空间 {}/{} 切片不可用（概览 Pod/工作负载/配额为「—」）：{}", clusterId, namespace, e.getMessage());
            return null;
        }
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
