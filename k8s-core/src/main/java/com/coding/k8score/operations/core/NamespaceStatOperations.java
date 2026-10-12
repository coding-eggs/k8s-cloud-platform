package com.coding.k8score.operations.core;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.NamespaceSnapshotDTO;
import com.coding.common.models.k8s.dto.NamespaceStatDTO;
import com.coding.k8score.operations.core.ClusterAggregationOperations.ClusterObjects;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 单个命名空间的行采样：该 ns 内 7 次 list（pods / 3 种 workload / services / resourcequotas / PVC），
 * 装配复用 {@link ClusterAggregationOperations#namespaceSnapshots} —— 与集群概览**同一个函数**，
 * 这是"两条采样路径不会漂移"的机制保证（不是靠人工对齐两段代码）。
 * <p>不采 PV / Node：它们是集群级事实，不属于任何命名空间。
 * <p>为什么单列一个类而不是给 {@link ClusterAggregationOperations} 加方法：后者的视野是
 * {@code .inAnyNamespace()} 的集群级一次 pass（admin client），而这里要走<b>租户可达</b>的
 * {@code .inNamespace(ns)} 窄查询（client 由访问模式决定，同 {@code WorkloadOperations}）。
 */
@RequiredArgsConstructor
public class NamespaceStatOperations {

    /** 与集群聚合同一族：pods + 3 workload + services + resourcequotas + pvc（PV/Node 属集群级，不取） */
    private final KubernetesClient client;

    /**
     * 单个命名空间的行。空 ns 名抛错（同既有 ops 的 {@link EnumResponseType#SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE}）。
     * <p>该 ns 内一个对象都没有时，{@code namespaceSnapshots} 的 union 里不会出现这个 ns 键 ——
     * 此时回 {@link #emptySnapshot} 的全零行，而不是 null：前端要能拿到「这个 ns 存在但什么都没跑」。
     */
    public NamespaceSnapshotDTO row(String namespace) {
        if (!StringUtils.hasText(namespace)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
        ClusterObjects objects = new ClusterObjects(
                client.pods().inNamespace(namespace).list().getItems(),
                client.apps().deployments().inNamespace(namespace).list().getItems(),
                client.apps().statefulSets().inNamespace(namespace).list().getItems(),
                client.apps().daemonSets().inNamespace(namespace).list().getItems(),
                client.services().inNamespace(namespace).list().getItems(),
                client.resourceQuotas().inNamespace(namespace).list().getItems(),
                client.persistentVolumeClaims().inNamespace(namespace).list().getItems(),
                List.of(), List.of());
        return ClusterAggregationOperations.namespaceSnapshots(objects, Instant.now().getEpochSecond())
                .getOrDefault(namespace, emptySnapshot(namespace, System.currentTimeMillis()));
    }

    /** 空命名空间也要有一行（全零、配额 null）—— "这个 ns 存在但什么都没跑" 是合法状态，不是缺数 */
    public static NamespaceSnapshotDTO emptySnapshot(String namespace, long sampledAtMillis) {
        NamespaceSnapshotDTO s = new NamespaceSnapshotDTO();
        NamespaceStatDTO stat = new NamespaceStatDTO();
        stat.setNamespace(namespace);
        s.setStat(stat);
        s.setAbnormalPodReasonCounts(new LinkedHashMap<>());
        s.setAbnormalPods(List.of());
        s.setUnhealthyWorkloads(List.of());
        s.setSampledAt(sampledAtMillis);
        return s;
    }
}
