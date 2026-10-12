package com.coding.k8score.operations.core;

import com.coding.common.models.k8s.dto.ClusterAggregateDTO;
import com.coding.common.models.k8s.dto.NamespaceSnapshotDTO;
import com.coding.common.models.k8s.dto.NamespaceStatDTO;
import com.coding.k8score.operations.core.ClusterAggregationOperations.ClusterObjects;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 按命名空间切片的装配（{@code ClusterAggregationOperations.namespaceSnapshots}）。
 * <p>本测试钉住两件事：
 * <ol>
 *   <li><b>切片不出错</b>：一个 ns 一份、空对象集不凭空造 ns、每 ns 的计数是<b>全量</b>（不是封顶后的名单条数）；</li>
 *   <li><b>两条采样路径不漂移</b>：全局聚合必须等于各切片之和 —— 这不是靠人工对齐，而是由实现里
 *       {@code aggregate} 直接派生自 {@code namespaceSnapshots} 保证的。</li>
 * </ol>
 */
class NamespaceSnapshotsTest {

    private Node node(String name) {
        return new NodeBuilder().withNewMetadata().withName(name).endMetadata().build();
    }

    private Pod crashing(String ns, String name) {
        return new PodBuilder()
                .withNewMetadata().withNamespace(ns).withName(name).endMetadata()
                .withNewStatus().withPhase("Running")
                .addNewContainerStatus().withName("c").withReady(false).withRestartCount(7)
                .withNewState().withNewWaiting().withReason("CrashLoopBackOff").endWaiting().endState()
                .endContainerStatus()
                .endStatus().build();
    }

    private ClusterObjects objects(List<Pod> pods, List<Deployment> deps) {
        return new ClusterObjects(pods, deps, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(node("n1")));
    }

    @Test
    void snapshots_are_partitioned_by_namespace_with_exact_counts() {
        var objects = objects(
                List.of(crashing("team-a", "p1"), crashing("team-a", "p2"), crashing("team-b", "p3")),
                List.of());

        Map<String, NamespaceSnapshotDTO> snaps = ClusterAggregationOperations.namespaceSnapshots(objects, 1_700_000_000L);

        assertThat(snaps.keySet()).containsExactly("team-a", "team-b");
        // 每 ns 的计数是全量、不是封顶后的条数
        assertThat(snaps.get("team-a").getAbnormalPodCount()).isEqualTo(2);
        assertThat(snaps.get("team-a").getAbnormalPodReasonCounts()).containsEntry("CrashLoopBackOff", 2);
        assertThat(snaps.get("team-a").getAbnormalPods()).hasSize(2);
        assertThat(snaps.get("team-b").getAbnormalPodCount()).isEqualTo(1);
        assertThat(snaps.get("team-a").getStat().getPodCount()).isEqualTo(2);
    }

    @Test
    void empty_namespace_produces_no_snapshot() {
        assertThat(ClusterAggregationOperations.namespaceSnapshots(objects(List.of(), List.of()), 1L)).isEmpty();
    }

    @Test
    void aggregate_matches_the_sum_of_snapshots() {
        var objects = objects(List.of(crashing("team-a", "p1"), crashing("team-b", "p2")), List.of());

        ClusterAggregateDTO agg = ClusterAggregationOperations.aggregate(objects, 1_700_000_000L);

        assertThat(agg.getAbnormalPodTotal()).isEqualTo(2);
        assertThat(agg.getAbnormalPodReasonCounts()).containsEntry("CrashLoopBackOff", 2);
        assertThat(agg.getNamespaces()).extracting(NamespaceStatDTO::getNamespace)
                .containsExactly("team-a", "team-b");
        assertThat(agg.getTotal().getNamespaceCount()).isEqualTo(2);
    }

    /**
     * 封顶只封「名单」，不封「计数」；且线上必须携带 per-ns 真值（{@code namespaceSnapshots}），
     * 否则平台侧无法精确回填 {@code ns:} 键。
     * <p>一个 ns 里放 201 个 CrashLoopBackOff 的 Pod（&gt; {@code ABNORMAL_POD_CAP=200}）：
     * 切片里 {@code abnormalPodCount} 必须是 201（全量），而 {@code abnormalPods} 名单封顶到 200；
     * 全局 {@code getAbnormalPods()} 也封顶 200，但 {@code getAbnormalPodTotal()} 仍是全量 201。
     * 这条钉死了「集群键切片不能被别人的对象挤掉」—— 也就是 platform-api 写 {@code ns:} 键必须
     * 用 {@code namespaceSnapshots} 而不是把全局封顶名单分桶的依据。
     */
    @Test
    void per_namespace_counts_stay_exact_while_lists_are_capped() {
        List<Pod> pods = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            pods.add(crashing("team-a", "p" + i));
        }
        ClusterAggregateDTO agg = ClusterAggregationOperations.aggregate(objects(pods, List.of()), 1_700_000_000L);

        NamespaceSnapshotDTO slice = agg.getNamespaceSnapshots().stream()
                .filter(s -> "team-a".equals(s.getStat().getNamespace()))
                .findFirst().orElseThrow();

        // per-ns 真值：计数全量（201）、名单封顶（200）
        assertThat(slice.getAbnormalPodCount()).isEqualTo(201);
        assertThat(slice.getAbnormalPods()).hasSize(200);
        assertThat(slice.getAbnormalPodReasonCounts()).containsEntry("CrashLoopBackOff", 201);
        // 全局：名单同样封顶，但计数仍是全量
        assertThat(agg.getAbnormalPods()).hasSize(200);
        assertThat(agg.getAbnormalPodTotal()).isEqualTo(201);
    }
}
