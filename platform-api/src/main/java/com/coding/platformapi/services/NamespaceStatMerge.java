package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.AbnormalPodDTO;
import com.coding.common.models.k8s.dto.ClusterTotalDTO;
import com.coding.common.models.k8s.dto.NamespaceSnapshotDTO;
import com.coding.common.models.k8s.dto.NamespaceStatDTO;
import com.coding.common.models.k8s.dto.UnhealthyWorkloadDTO;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 由 ns 切片合并出「合计 + 两个名单」（纯函数，集群概览与租户概览共用同一份口径）。
 * <p>作用域拆键后，一屏上的「合计」不再是别处存好的一个数，而是本次拿到的那些 ns 切片合并而来
 * —— 于是「同一屏合计 = Σ明细」恒成立（不需要对齐两份数据）。
 *
 * <h2>为什么把排序/封顶规则搬进来（而不是从 k8s-core 暴露）</h2>
 * platform-api <b>不依赖 k8s-core</b>（见 {@code NamespaceStatServiceTest} 的既定立场），
 * 所以 {@code ClusterAggregationOperations} 的 {@code sortAndCap} / {@code severity} /
 * {@code orderReasonCounts} 在这里<b>照抄一份</b>。这是本类唯一与 k8s-core 重复的地方，
 * 判据（severity 表、封顶值、定序键）必须两边一致；改口径时两处一起看。
 * <p><b>但</b>合并的输入换成「本页合并结果」而非"集群全量"是刻意的：同一屏的数永远自洽。
 */
public final class NamespaceStatMerge {

    /** 异常 Pod 明细封顶（同 k8s-core {@code ClusterAggregationOperations.ABNORMAL_POD_CAP}）。 */
    public static final int ABNORMAL_POD_CAP = 200;

    /** 不健康工作负载明细封顶（同上）。 */
    public static final int UNHEALTHY_WORKLOAD_CAP = 200;

    private NamespaceStatMerge() {
    }

    /**
     * 合计一行 = Σ 各切片的行。{@code namespaceCount} = 切片数。
     * <p><b>不含 {@code pvCount}</b>：PV 是 cluster-scoped，任何单个 ns 切片里都没有它 ——
     * 需要的人（集群概览）从集群键的 storage 里补（见 {@code ClusterService}）。
     */
    public static ClusterTotalDTO total(List<NamespaceSnapshotDTO> slices) {
        ClusterTotalDTO t = new ClusterTotalDTO();
        List<NamespaceSnapshotDTO> list = orEmpty(slices);
        t.setCpuRequest(BigDecimal.ZERO);
        t.setMemRequest(BigDecimal.ZERO);
        t.setCpuLimit(BigDecimal.ZERO);
        t.setMemLimit(BigDecimal.ZERO);
        for (NamespaceSnapshotDTO s : list) {
            NamespaceStatDTO r = s.getStat();
            if (r == null) {
                continue;
            }
            t.setCpuRequest(add(t.getCpuRequest(), r.getCpuRequest()));
            t.setMemRequest(add(t.getMemRequest(), r.getMemRequest()));
            t.setCpuLimit(add(t.getCpuLimit(), r.getCpuLimit()));
            t.setMemLimit(add(t.getMemLimit(), r.getMemLimit()));
            t.setPodCount(t.getPodCount() + r.getPodCount());
            t.setDeploymentCount(t.getDeploymentCount() + r.getDeployCount());
            t.setStatefulsetCount(t.getStatefulsetCount() + r.getStsCount());
            t.setDaemonsetCount(t.getDaemonsetCount() + r.getDsCount());
            t.setServiceCount(t.getServiceCount() + r.getSvcCount());
            t.setPvcCount(t.getPvcCount() + r.getPvcCount());
        }
        t.setNamespaceCount(list.size());
        return t;
    }

    /** 异常 Pod 按 reason 分组计数：各切片<b>求和</b>（全量，不受封顶影响），顺序 = 严重度降序 → 计数降序。 */
    public static Map<String, Integer> abnormalReasonCounts(List<NamespaceSnapshotDTO> slices) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (NamespaceSnapshotDTO s : orEmpty(slices)) {
            if (s.getAbnormalPodReasonCounts() != null) {
                s.getAbnormalPodReasonCounts().forEach((reason, n) -> counts.merge(reason, n, Integer::sum));
            }
        }
        return orderReasonCounts(counts);
    }

    /** 异常 Pod 总数（未封顶）= Σ 各切片的全量计数。 */
    public static int abnormalTotal(List<NamespaceSnapshotDTO> slices) {
        return orEmpty(slices).stream().mapToInt(NamespaceSnapshotDTO::getAbnormalPodCount).sum();
    }

    /** 不健康工作负载总数（未封顶）= Σ 各切片的全量计数。 */
    public static int unhealthyTotal(List<NamespaceSnapshotDTO> slices) {
        return orEmpty(slices).stream().mapToInt(NamespaceSnapshotDTO::getUnhealthyWorkloadCount).sum();
    }

    /** 合并各切片的异常 Pod 名单 → 按 severity 排序 → 封顶。 */
    public static List<AbnormalPodDTO> abnormalPods(List<NamespaceSnapshotDTO> slices, int cap) {
        List<AbnormalPodDTO> all = new ArrayList<>();
        for (NamespaceSnapshotDTO s : orEmpty(slices)) {
            all.addAll(orEmpty(s.getAbnormalPods()));
        }
        return all.stream()
                .sorted(Comparator.comparingInt((AbnormalPodDTO p) -> severity(p.getReason())).reversed()
                        .thenComparing(Comparator.comparingInt(AbnormalPodDTO::getRestarts).reversed())
                        .thenComparing(AbnormalPodDTO::getNamespace, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(AbnormalPodDTO::getName, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(cap)
                .toList();
    }

    /** 合并各切片的不健康工作负载名单 → 缺得最多的排前面 → 封顶。 */
    public static List<UnhealthyWorkloadDTO> unhealthyWorkloads(List<NamespaceSnapshotDTO> slices, int cap) {
        List<UnhealthyWorkloadDTO> all = new ArrayList<>();
        for (NamespaceSnapshotDTO s : orEmpty(slices)) {
            all.addAll(orEmpty(s.getUnhealthyWorkloads()));
        }
        return all.stream()
                .sorted(Comparator.comparingInt((UnhealthyWorkloadDTO w) -> w.getReplicas() - w.getReadyReplicas()).reversed()
                        .thenComparing(UnhealthyWorkloadDTO::getNamespace, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(UnhealthyWorkloadDTO::getName, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(cap)
                .toList();
    }

    /** reason 计数的稳定顺序：严重度降序 → 计数降序（单份统计与切片求和共用这一处排序）。 */
    public static Map<String, Integer> orderReasonCounts(Map<String, Integer> counts) {
        Map<String, Integer> out = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, Integer> e) -> severity(e.getKey())).reversed()
                        .thenComparing(Map.Entry::getValue, Comparator.<Integer>reverseOrder()))
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /**
     * 归组用的严重度（口径见 {@code AbnormalPodDTO} 与 k8s-core 的实现，两处必须一致）：
     * CrashLoopBackOff &gt; OOMKilled &gt; ImagePullBackOff &gt; ErrImagePull &gt; Error &gt; Failed &gt; Unknown &gt; Pending。
     */
    public static int severity(String reason) {
        return switch (reason == null ? "" : reason) {
            case "CrashLoopBackOff" -> 100;
            case "OOMKilled" -> 90;
            case "ImagePullBackOff" -> 80;
            case "ErrImagePull" -> 70;
            case "Error" -> 60;
            case "Failed" -> 50;
            case "Unknown" -> 40;
            case "Pending" -> 30;
            default -> 0;
        };
    }

    private static BigDecimal add(BigDecimal a, BigDecimal b) {
        if (a == null) {
            a = BigDecimal.ZERO;
        }
        return b == null ? a : a.add(b);
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
