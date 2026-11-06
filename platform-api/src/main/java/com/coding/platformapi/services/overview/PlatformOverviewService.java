package com.coding.platformapi.services.overview;

import com.coding.common.models.k8s.dto.ClusterSnapshotDTO;
import com.coding.common.models.k8s.dto.ClusterTotalDTO;
import com.coding.common.models.k8s.dto.NamespaceSnapshotDTO;
import com.coding.common.models.k8s.dto.NamespaceStatDTO;
import com.coding.common.models.k8s.dto.NodeHealthDTO;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.auth.PlatformUserTenantMapper;
import com.coding.data.mapper.k8s.PlatformAlertEventMapper;
import com.coding.data.mapper.system.PlatformApplicationMapper;
import com.coding.data.models.auth.PlatformTenant;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.platformapi.models.overview.CountBreakdown;
import com.coding.platformapi.models.overview.PlatformOverviewView;
import com.coding.platformapi.models.overview.PlatformOverviewView.Capacity;
import com.coding.platformapi.models.overview.PlatformOverviewView.Clusters;
import com.coding.platformapi.models.overview.PlatformOverviewView.Namespaces;
import com.coding.platformapi.models.overview.PlatformOverviewView.Nodes;
import com.coding.platformapi.models.overview.PlatformOverviewView.Pods;
import com.coding.platformapi.models.overview.PlatformOverviewView.Quota;
import com.coding.platformapi.models.overview.PlatformOverviewView.Tenants;
import com.coding.platformapi.models.overview.PlatformOverviewView.Workloads;
import com.coding.platformapi.services.ClusterService;
import com.coding.platformapi.services.MiddlewareService;
import com.coding.platformapi.services.NamespaceStatMerge;
import com.coding.platformapi.services.NamespaceStatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 平台总览：全部启用集群按资源类型汇总（不按集群拆，看单个集群去集群概览）。
 * <p>集群级事实（节点 / 容量 / ns 名单）取自聚合快照；Pod / 工作负载 / 配额由 ns 切片合并
 * （同 {@code ClusterService.overview} 口径）；应用 / 告警走 DB 计数；中间件走实时状态（45s 缓存）。
 * 某集群快照取不到 → 记为不可用，它的 k8s 侧数字不计入合计，DB 侧（应用 / 告警）照算。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformOverviewService {

    private final ClusterService clusters;
    private final NamespaceStatService stats;
    private final MiddlewareService middleware;
    private final PlatformApplicationMapper apps;
    private final PlatformAlertEventMapper alerts;
    private final PlatformTenantMapper tenants;
    private final PlatformUserTenantMapper userTenants;
    private final PlatformTenantNamespaceMapper allocations;
    private final PlatformHealthProbe health;

    public PlatformOverviewView overview() {
        List<K8sCluster> enabled = clusters.list().stream()
                .filter(c -> Integer.valueOf(1).equals(c.getEnabled()))
                .toList();
        List<PlatformTenantNamespace> allocRows = orEmpty(allocations.listAll());
        Map<String, Set<String>> allocatedByCluster = allocRows.stream()
                .collect(Collectors.groupingBy(PlatformTenantNamespace::getClusterId,
                        Collectors.mapping(PlatformTenantNamespace::getNamespace, Collectors.toSet())));

        int connected = 0;
        List<String> unavailable = new ArrayList<>();
        List<NamespaceSnapshotDTO> slices = new ArrayList<>();
        List<CountBreakdown> mw = new ArrayList<>();
        long ready = 0, notReady = 0, nsAllocated = 0, nsUnallocated = 0, nsSystem = 0;
        BigDecimal cpuAlloc = null, memAlloc = null;

        for (K8sCluster c : enabled) {
            ClusterSnapshotDTO snap = clusters.snapshot(c.getClusterId());
            List<NamespaceSnapshotDTO> clusterSlices = snap == null ? null : slicesOf(c.getClusterId(), snap);
            if (clusterSlices == null) {
                unavailable.add(c.getClusterName() == null ? c.getClusterId() : c.getClusterName());
                continue;
            }
            connected++;
            slices.addAll(clusterSlices);
            for (NodeHealthDTO n : orEmpty(snap.getNodeHealth())) {
                if (n.isReady()) {
                    ready++;
                } else {
                    notReady++;
                }
            }
            if (snap.getNodeCapacity() != null) {
                cpuAlloc = add(cpuAlloc, snap.getNodeCapacity().getCpuAllocatable());
                memAlloc = add(memAlloc, snap.getNodeCapacity().getMemoryAllocatable());
            }
            Set<String> allocated = allocatedByCluster.getOrDefault(c.getClusterId(), Set.of());
            for (String ns : orEmpty(snap.getNamespaceNames())) {
                switch (NamespaceCategory.of(ns, allocated)) {
                    case SYSTEM -> nsSystem++;
                    case ALLOCATED -> nsAllocated++;
                    case UNALLOCATED -> nsUnallocated++;
                }
            }
            mw.add(middlewareOf(c.getClusterId()));
        }

        ClusterTotalDTO total = NamespaceStatMerge.total(slices);
        long abnormal = Math.max(0, total.getPodCount() - total.getPodRunning() - total.getPodPending() - total.getPodSucceeded());
        Capacity capacity = connected == 0 ? null
                : new Capacity(total.getCpuRequest(), cpuAlloc, total.getMemRequest(), memAlloc);

        return new PlatformOverviewView(
                health.probe(),
                new Clusters(connected, unavailable.size(), unavailable),
                OverviewBuckets.alerts(orEmpty(alerts.countFiringBySeverity(null, null))),
                OverviewBuckets.applications(orEmpty(apps.countByStatus(null, null))),
                OverviewBuckets.sum(mw),
                new Workloads(total.getDeploymentCount() + total.getStatefulsetCount() + total.getDaemonsetCount(),
                        NamespaceStatMerge.unhealthyTotal(slices)),
                new Pods(total.getPodCount(), total.getPodRunning(), total.getPodPending(), total.getPodSucceeded(), abnormal),
                tenants(allocRows.size()),
                capacity,
                new Nodes(ready, notReady),
                new Namespaces(nsAllocated, nsUnallocated, nsSystem),
                quota(slices));
    }

    /** ns 切片；取不到（网关异常）→ null，整个集群按不可用处理（与快照为 null 同语义）。 */
    private List<NamespaceSnapshotDTO> slicesOf(String clusterId, ClusterSnapshotDTO snap) {
        try {
            return orEmpty(stats.rows(null, clusterId, snap.getNamespaceNames())).stream()
                    .filter(Objects::nonNull)
                    .filter(s -> s.getStat() != null)
                    .toList();
        } catch (Exception e) {
            log.warn("集群 {} 的 ns 切片不可用（平台总览按不可用计）：{}", clusterId, e.getMessage());
            return null;
        }
    }

    private CountBreakdown middlewareOf(String clusterId) {
        try {
            return OverviewBuckets.middleware(orEmpty(middleware.liveStatuses(clusterId)).stream()
                    .map(MiddlewareService.LiveStatus::status).toList());
        } catch (Exception e) {
            log.warn("集群 {} 中间件状态不可用（平台总览不计入）：{}", clusterId, e.getMessage());
            return null;
        }
    }

    private Tenants tenants(long allocatedNamespaces) {
        List<PlatformTenant> all = orEmpty(tenants.listAll());
        long on = all.stream().filter(t -> t.getStatus() != null && t.getStatus() == 1).count();
        return new Tenants(all.size(), on, all.size() - on, userTenants.countDistinctUsers(null), allocatedNamespaces);
    }

    private static Quota quota(List<NamespaceSnapshotDTO> slices) {
        BigDecimal cpuUsed = null, cpuHard = null, memUsed = null, memHard = null;
        for (NamespaceSnapshotDTO s : slices) {
            NamespaceStatDTO r = s.getStat();
            if (r.getQuotaCpuHard() != null) {
                cpuHard = add(cpuHard, r.getQuotaCpuHard());
                cpuUsed = add(cpuUsed == null ? BigDecimal.ZERO : cpuUsed, r.getQuotaCpuUsed());
            }
            if (r.getQuotaMemHard() != null) {
                memHard = add(memHard, r.getQuotaMemHard());
                memUsed = add(memUsed == null ? BigDecimal.ZERO : memUsed, r.getQuotaMemUsed());
            }
        }
        return cpuHard == null && memHard == null ? null : new Quota(cpuUsed, cpuHard, memUsed, memHard);
    }

    private static BigDecimal add(BigDecimal a, BigDecimal b) {
        if (b == null) {
            return a;
        }
        return a == null ? b : a.add(b);
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
