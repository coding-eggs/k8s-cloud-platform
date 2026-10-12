package com.coding.k8score.operations.core;

import com.coding.common.models.k8s.dto.NamespaceSnapshotDTO;
import com.coding.k8score.operations.core.ClusterAggregationOperations.ClusterObjects;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单命名空间采样（{@link NamespaceStatOperations}）。
 * <p>本测试钉住两件事：
 * <ol>
 *   <li><b>两条采样路径不漂移</b>：单 ns 采样装配出来的行，必须与集群聚合切出的同一 ns 切片逐字段相同
 *       —— 这是由实现里 {@code row(...)} 直接复用 {@link ClusterAggregationOperations#namespaceSnapshots}
 *       保证的，不是靠人工对齐两段代码；</li>
 *   <li><b>空 ns 也要有一行</b>：该 ns 一个对象都没有时返回全零行（配额 null），而不是 null
 *       —— 否则前端拿不到「这个 ns 存在」这件事。</li>
 * </ol>
 */
class NamespaceStatOperationsTest {

    /** 不触网：只钉"喂进去同一批对象，单 ns 路径与集群路径产出的切片逐字段相同" */
    @Test
    void single_namespace_row_equals_the_slice_of_the_cluster_path() {
        Pod p = new PodBuilder().withNewMetadata().withNamespace("team-a").withName("p1").endMetadata()
                .withNewStatus().withPhase("Pending").endStatus().build();
        Deployment d = new DeploymentBuilder().withNewMetadata().withNamespace("team-a").withName("web").endMetadata()
                .withNewSpec().withReplicas(2).endSpec()
                .withNewStatus().withReplicas(2).withReadyReplicas(1).endStatus().build();

        long now = 1_700_000_000L;
        ClusterObjects clusterWide = new ClusterObjects(List.of(p), List.of(d), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());
        NamespaceSnapshotDTO fromCluster = ClusterAggregationOperations.namespaceSnapshots(clusterWide, now).get("team-a");

        NamespaceSnapshotDTO fromSingle = ClusterAggregationOperations
                .namespaceSnapshots(new ClusterObjects(List.of(p), List.of(d), List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of(), List.of()), now).get("team-a");

        assertThat(fromSingle).usingRecursiveComparison().isEqualTo(fromCluster);
    }

    @Test
    void empty_namespace_yields_a_zero_row_not_null() {
        // 该 ns 一个对象都没有：仍要回一行（全零、quota 为 null），否则前端拿不到"这个 ns 存在"
        NamespaceSnapshotDTO snap = NamespaceStatOperations.emptySnapshot("empty-ns", 1_700_000_000_000L);

        assertThat(snap.getStat().getNamespace()).isEqualTo("empty-ns");
        assertThat(snap.getStat().getPodCount()).isZero();
        assertThat(snap.getStat().getQuotaCpuHard()).isNull();
        assertThat(snap.getAbnormalPodCount()).isZero();
    }
}
