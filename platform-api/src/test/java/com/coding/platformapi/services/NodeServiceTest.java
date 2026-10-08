package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.NodeDTO;
import com.coding.common.models.k8s.dto.NodePodStatDTO;
import com.coding.platformapi.k8s.K8sClient;
import com.coding.platformapi.k8s.K8sNodeClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 节点业务层：list/get 的「节点 + podstats 两次请求」不变式。
 *
 * <p>回归守卫的重点是<b>请求次数</b>，不是返回值——改造前 list 是 N+1：先查一次 podstats 建了 map 却
 * 未使用（死代码），随后循环里逐节点调用一个每次都重新发 podstats 请求的方法，10 个节点 = 11 次 HTTP。
 * 这类退化不会有任何编译或功能症状，只有断言调用次数才能拦住。
 */
class NodeServiceTest {

    private final K8sClient k8s = mock(K8sClient.class);
    private final K8sNodeClient nodeClient = mock(K8sNodeClient.class);
    private final NodeService svc = new NodeService(k8s, nodeClient);

    private NodeDTO node(String name) {
        NodeDTO d = new NodeDTO();
        d.setName(name);
        d.setClusterId("c1");
        return d;
    }

    private NodePodStatDTO stat(String name, long podCount, long cpu, long mem) {
        NodePodStatDTO s = new NodePodStatDTO();
        s.setNodeName(name);
        s.setPodCount(podCount);
        s.setCpuRequestMillicores(cpu);
        s.setMemRequestBytes(mem);
        return s;
    }

    private NodeDTO query() {
        NodeDTO q = new NodeDTO();
        q.setClusterId("c1");
        return q;
    }

    @Test
    void list_issues_podstats_exactly_once_for_many_nodes() {
        when(k8s.list(any(NodeDTO.class))).thenReturn(List.of(node("n1"), node("n2"), node("n3")));
        when(nodeClient.podStats("c1")).thenReturn(List.of(
                stat("n1", 1, 100, 1000),
                stat("n2", 2, 200, 2000),
                stat("n3", 3, 300, 3000)));

        List<NodeDTO> out = svc.list(query());

        assertThat(out).extracting(NodeDTO::getPodCount).containsExactly(1L, 2L, 3L);
        assertThat(out).extracting(NodeDTO::getCpuRequestMillicores).containsExactly(100L, 200L, 300L);
        assertThat(out).extracting(NodeDTO::getMemRequestBytes).containsExactly(1000L, 2000L, 3000L);
        // N+1 回归守卫：3 个节点也只能有一次 podstats
        verify(nodeClient, times(1)).podStats("c1");
        verify(k8s, times(1)).list(any(NodeDTO.class));
    }

    @Test
    void list_leaves_stats_null_for_node_absent_from_podstats() {
        // 刚加入/不可调度节点在聚合结果里可能没有条目 → 字段保持 null（前端显示「—」），不能填 0
        when(k8s.list(any(NodeDTO.class))).thenReturn(List.of(node("n1"), node("n-new")));
        when(nodeClient.podStats("c1")).thenReturn(List.of(stat("n1", 5, 500, 5000)));

        List<NodeDTO> out = svc.list(query());

        assertThat(out.get(0).getPodCount()).isEqualTo(5L);
        assertThat(out.get(1).getPodCount()).isNull();
        assertThat(out.get(1).getCpuRequestMillicores()).isNull();
    }

    @Test
    void list_skips_podstats_join_when_no_nodes() {
        when(k8s.list(any(NodeDTO.class))).thenReturn(List.of());
        when(nodeClient.podStats("c1")).thenReturn(List.of());

        assertThat(svc.list(query())).isEmpty();
        verify(nodeClient, times(1)).podStats("c1");
    }

    @Test
    void get_returns_null_without_fetching_podstats() {
        when(k8s.get(any(NodeDTO.class))).thenReturn(null);
        assertThat(svc.get("nope", "c1")).isNull();
        verify(nodeClient, never()).podStats(anyString());
    }

    @Test
    void get_fills_stats_from_single_podstats_call() {
        when(k8s.get(any(NodeDTO.class))).thenReturn(node("n1"));
        when(nodeClient.podStats("c1")).thenReturn(List.of(stat("n1", 7, 700, 7000)));

        NodeDTO out = svc.get("n1", "c1");

        assertThat(out.getPodCount()).isEqualTo(7L);
        verify(nodeClient, times(1)).podStats("c1");
    }
}
