package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.NamespaceSnapshotDTO;
import com.coding.platformapi.cache.RedisJsonCache;
import com.coding.platformapi.k8s.K8sNamespaceStatClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 命名空间切片的**唯一读写入口**：缓存优先、未命中采样、必要时补采。
 * <p>三个消费者共用它（租户概览 / 集群概览明细 / 命名空间详情页），所以口径与新鲜度规则只需要在这里成立一次。
 * <p><b>页内一致性阈值</b>：调用方要一次拿到多行时（"多行拼一个数"），若各行 sampledAt 相差超过
 * {@link #SKEW_TOLERANCE_MILLIS}，把**过旧的那些**在本次请求内重采 —— 否则页面上的"合计"会是
 * 几个不同时刻的行硬加出来的数（那个数在任何瞬间都不曾存在）。补完即自愈：后续 15s 内的读取不再触发。
 * 单行消费者（命名空间页）不走这条：它本来就只一行，没有"页内"可言。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NamespaceStatService {

    static final Duration TTL = Duration.ofSeconds(45);
    static final long SKEW_TOLERANCE_MILLIS = 15_000L;
    private static final TypeReference<NamespaceSnapshotDTO> TYPE = new TypeReference<>() {};

    private final RedisJsonCache cache;
    private final K8sNamespaceStatClient client;

    private static String key(String clusterId, String namespace) {
        return "ns:" + clusterId + ":" + namespace;
    }

    public NamespaceSnapshotDTO row(String tenantId, String clusterId, String namespace) {
        return cache.get(key(clusterId, namespace), TTL, TYPE, () -> sample(tenantId, clusterId, namespace));
    }

    public List<NamespaceSnapshotDTO> rows(String tenantId, String clusterId, Collection<String> namespaces) {
        List<String> wanted = namespaces == null ? List.of() : namespaces.stream().sorted().toList();
        if (wanted.isEmpty()) {
            return List.of();
        }
        Map<String, NamespaceSnapshotDTO> hits = cache.getMany(
                wanted.stream().map(ns -> key(clusterId, ns)).toList(), TYPE);
        Map<String, NamespaceSnapshotDTO> byNs = new LinkedHashMap<>();
        for (String ns : wanted) {
            NamespaceSnapshotDTO cached = hits.get(key(clusterId, ns));
            if (cached != null) {
                byNs.put(ns, cached);
            }
        }
        long newest = byNs.values().stream().mapToLong(NamespaceSnapshotDTO::getSampledAt).max().orElse(0L);
        Map<String, NamespaceSnapshotDTO> toWrite = new LinkedHashMap<>();
        List<NamespaceSnapshotDTO> out = new ArrayList<>();
        for (String ns : wanted) {
            NamespaceSnapshotDTO s = byNs.get(ns);
            boolean stale = s != null && newest - s.getSampledAt() > SKEW_TOLERANCE_MILLIS;
            if (s == null || stale) {
                s = sample(tenantId, clusterId, ns);   // 未命中 或 过旧 → 本次请求内补齐
                toWrite.put(key(clusterId, ns), s);
            }
            out.add(s);
        }
        cache.setMany(toWrite, TTL);
        return out;
    }

    /**
     * 按"有没有租户上下文"选 k8s-server 入口：{@code tenantId != null} → 租户边界（{@code GET /namespacestats/{namespace}}）；
     * {@code tenantId == null} → 平台模式（{@code POST /namespacestats/platform/get}，PLATFORM + admin client）。
     * <p><b>{@code tenantId == null} 的语义就是"平台模式"</b>，不是"忘了传"：平台侧页面（集群概览明细、命名空间详情页）
     * 读的是不含租户维度的行。两个入口写的是**同一个** {@code ns:<clusterId>:<namespace>} 键（本类的 {@link #key}），
     * 故"一个事实一个键"不受影响。
     */
    private NamespaceSnapshotDTO sample(String tenantId, String clusterId, String namespace) {
        return tenantId == null
                ? client.platformRow(clusterId, namespace)
                : client.row(tenantId, clusterId, namespace);
    }

    /**
     * 一次采样填 N 个键（同一个 {@code sampledAt}）—— 由集群那次聚合的切片来，
     * 窄读（租户概览 / 命名空间页）永远不必为此再扫全集群。
     * <p><b>ns 键的拼法只在这里出现一次</b>：调用方（{@code ClusterService}）只管给切片，
     * 键由本方法拼 —— 不许在别处再写一遍 {@code "ns:" + clusterId + ":" + ns}。
     */
    public void putClusterSlices(String clusterId, List<NamespaceSnapshotDTO> slices) {
        if (slices == null || slices.isEmpty()) {
            return;
        }
        Map<String, NamespaceSnapshotDTO> values = new LinkedHashMap<>();
        slices.forEach(s -> values.put(key(clusterId, s.getStat().getNamespace()), s));
        cache.setMany(values, TTL);
    }
}
