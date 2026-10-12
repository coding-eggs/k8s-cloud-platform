package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.NamespaceSnapshotDTO;
import com.coding.common.models.k8s.dto.NamespaceStatDTO;
import com.coding.common.models.k8s.dto.NamespaceStatEntry;
import com.coding.common.models.k8s.dto.QuotaState;
import com.coding.common.models.k8s.dto.TenantClusterSummaryDTO;
import com.coding.common.models.k8s.dto.TenantOverviewDTO;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.data.models.k8s.K8sCluster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 租户概览：范围取自分配表（平台侧独有，**不经 {@code ClusterService}** —— 租户的命名空间集合不是集群快照），
 * 事实取自 ns 切片（{@link NamespaceStatService}，缓存优先），合并只在这里做一次。
 *
 * <p><b>顺序、逐集群捕获</b>：
 * <ol>
 *   <li>① 不能跨线程扇出：{@code K8sServerGateway} 的 token 透传靠 {@code RequestContextHolder}（线程绑定），
 *       换线程即 401；</li>
 *   <li>② 一个集群不可用不能拖垮整页（B4 在 {@code Promise.all} 一锅端上踩过），故**逐集群 try/catch**，
 *       失败的集群进 {@code unavailableClusters}，其余照常出数（spec §10）。</li>
 * </ol>
 *
 * <p><b>null 切片</b>：{@link NamespaceStatService#rows} 的网关异常路径可能 append 进 null，而
 * {@link NamespaceStatMerge} 的各方法会直接解引用切片 —— 所以每拿到一批切片，先在
 * {@link #nonNull(List)} 里滤掉 null 再合并。降级语义是"少算 + 标记"，不是抛。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantOverviewService {

    /** 租户概览首屏异常 Pod 名单封顶（spec §8：50；集群页是 200）。 */
    static final int ABNORMAL_POD_CAP = 50;

    private final PlatformTenantNamespaceMapper allocationMapper;
    private final K8sClusterMapper clusterMapper;
    private final NamespaceStatService statService;

    /**
     * 首屏快照：合计 / 逐集群小计 / 两个名单 / 配额三档，全部由本页拿到的切片现算。
     */
    public TenantOverviewDTO overview(String tenantId) {
        Map<String, List<String>> nsByCluster = allocationsByCluster(tenantId);

        List<NamespaceSnapshotDTO> all = new ArrayList<>();
        List<TenantClusterSummaryDTO> clusters = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : nsByCluster.entrySet()) {
            TenantClusterSummaryDTO c = new TenantClusterSummaryDTO();
            c.setClusterId(e.getKey());
            c.setClusterName(clusterNameOf(e.getKey()));
            c.setNamespaceCount(e.getValue().size());
            try {
                List<NamespaceSnapshotDTO> slices = nonNull(statService.rows(tenantId, e.getKey(), e.getValue()));
                all.addAll(slices);
                c.setTotal(NamespaceStatMerge.total(slices));
                c.setAvailable(true);
            } catch (Exception ex) {
                log.warn("租户 {} 的集群 {} 概览数据不可用：{}", tenantId, e.getKey(), ex.getMessage());
                c.setAvailable(false);
                unavailable.add(e.getKey());
            }
            clusters.add(c);
        }
        return assemble(tenantId, all, clusters, unavailable);   // 合计/名单/分档全部由 all 现算
    }

    /**
     * 资源明细：per-namespace 行（每行回带 {@code clusterId} + 配额三档状态），懒加载。
     * <p>与 {@link #overview} 同源（同一分配表范围 + 同一 ns 切片），所以两个 tab 的数字天然一致。
     * 逐集群降级：某集群取不到数时，它的行不进表，其余集群照常。
     */
    public List<NamespaceStatEntry> breakdown(String tenantId) {
        List<NamespaceStatEntry> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : allocationsByCluster(tenantId).entrySet()) {
            try {
                for (NamespaceSnapshotDTO s : nonNull(statService.rows(tenantId, e.getKey(), e.getValue()))) {
                    out.add(new NamespaceStatEntry(e.getKey(), s, QuotaClassifier.state(s.getStat())));
                }
            } catch (Exception ex) {
                log.warn("租户 {} 的集群 {} 明细数据不可用：{}", tenantId, e.getKey(), ex.getMessage());
            }
        }
        return out;
    }

    /** 范围：分配表 → (clusterId → [namespace])；集群按 id 定序，同一请求内的多次调用顺序一致。 */
    private Map<String, List<String>> allocationsByCluster(String tenantId) {
        List<PlatformTenantNamespace> allocations = allocationMapper.listByTenant(tenantId);
        if (allocations == null || allocations.isEmpty()) {
            return Map.of();
        }
        return allocations.stream().collect(Collectors.groupingBy(PlatformTenantNamespace::getClusterId,
                TreeMap::new, Collectors.mapping(PlatformTenantNamespace::getNamespace, Collectors.toList())));
    }

    private TenantOverviewDTO assemble(String tenantId, List<NamespaceSnapshotDTO> all,
                                       List<TenantClusterSummaryDTO> clusters, List<String> unavailable) {
        TenantOverviewDTO out = new TenantOverviewDTO();
        out.setTenantId(tenantId);
        out.setSampledAt(oldest(all));
        out.setTotal(NamespaceStatMerge.total(all));
        out.setClusters(clusters);
        out.setUnavailableClusters(unavailable);
        out.setAbnormalPodTotal(NamespaceStatMerge.abnormalTotal(all));
        out.setAbnormalPodReasonCounts(NamespaceStatMerge.abnormalReasonCounts(all));
        out.setAbnormalPods(NamespaceStatMerge.abnormalPods(all, ABNORMAL_POD_CAP));
        out.setUnhealthyWorkloadTotal(NamespaceStatMerge.unhealthyTotal(all));
        out.setUnhealthyWorkloads(NamespaceStatMerge.unhealthyWorkloads(all, NamespaceStatMerge.UNHEALTHY_WORKLOAD_CAP));

        List<String> withoutQuota = new ArrayList<>();
        List<String> partialQuota = new ArrayList<>();
        BigDecimal cpuHard = null;
        BigDecimal memHard = null;
        BigDecimal cpuUsed = null;
        BigDecimal memUsed = null;
        for (NamespaceSnapshotDTO s : all) {
            NamespaceStatDTO st = s == null ? null : s.getStat();
            QuotaState state = QuotaClassifier.state(st);   // 三档判定只此一处（R7）
            if (st != null && st.getNamespace() != null) {
                if (state == QuotaState.MISSING) {
                    withoutQuota.add(st.getNamespace());
                } else if (state == QuotaState.PARTIAL) {
                    partialQuota.add(st.getNamespace());
                }
            }
            if (st != null) {   // 只累加**有值**的配额列：无配额的行贡献 null，合计保持 null（不是 0）
                cpuHard = add(cpuHard, st.getQuotaCpuHard());
                memHard = add(memHard, st.getQuotaMemHard());
                cpuUsed = add(cpuUsed, st.getQuotaCpuUsed());
                memUsed = add(memUsed, st.getQuotaMemUsed());
            }
        }
        out.setQuotaCpuHard(cpuHard);
        out.setQuotaMemHard(memHard);
        out.setQuotaCpuUsed(cpuUsed);
        out.setQuotaMemUsed(memUsed);
        out.setNamespacesWithoutQuota(withoutQuota);
        out.setNamespacesPartialQuota(partialQuota);
        return out;
    }

    private String clusterNameOf(String clusterId) {
        K8sCluster cluster = clusterMapper.selectByPrimaryKey(clusterId);
        return cluster == null ? null : cluster.getClusterName();
    }

    /** 本页最旧的一条采样时刻（spec §6.3「数据截至」）；无行 → 0。 */
    private static long oldest(List<NamespaceSnapshotDTO> all) {
        return all.stream().mapToLong(NamespaceSnapshotDTO::getSampledAt).min().orElse(0L);
    }

    /** 滤掉 null 切片（网关异常路径可能 append 进 null；合并函数会直接解引用）。 */
    private static List<NamespaceSnapshotDTO> nonNull(List<NamespaceSnapshotDTO> slices) {
        if (slices == null || slices.isEmpty()) {
            return List.of();
        }
        return slices.stream().filter(Objects::nonNull).toList();
    }

    private static BigDecimal add(BigDecimal acc, BigDecimal v) {
        if (v == null) {
            return acc;
        }
        return acc == null ? v : acc.add(v);
    }
}
